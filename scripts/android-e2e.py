"""Exercise the shipped UI and foreground service on an isolated Android emulator.

Only synthetic numbers/POS credentials are used. Telegram remains disabled.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

APP = "com.alphapos.operatorlink"
DEVICE = os.environ.get("ANDROID_SERIAL", "emulator-5554")
OUT = Path("ci/e2e")
OUT.mkdir(parents=True, exist_ok=True)


def adb(*args, binary=False, timeout=40, check=True):
    result = subprocess.run(["adb", "-s", DEVICE, *map(str, args)], capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"adb {args}: {result.stderr.decode(errors='replace')}")
    return result.stdout if binary else result.stdout.decode(errors="replace").replace("\r", "")


def stage(message):
    print(message, flush=True)
    with (OUT / "results.txt").open("a", encoding="utf-8") as output:
        output.write(message + "\n")


def ui():
    for attempt in range(3):
        try:
            # A failed dump must never reuse an XML file from the previous screen.
            adb("shell", "rm", "-f", "/sdcard/operator-ui.xml")
            # Android 13's legacy dumper can dereference a missing child in
            # Files' decorative accessibility nodes. Its standard compressed
            # hierarchy excludes unimportant views without changing the app.
            options = ("--compressed",) if attempt else ()
            adb("shell", "uiautomator", "dump", *options, "/sdcard/operator-ui.xml", timeout=25)
            raw = adb("exec-out", "cat", "/sdcard/operator-ui.xml")
            return ET.fromstring(raw[raw.index("<?xml"):])
        except (ValueError, ET.ParseError, subprocess.TimeoutExpired, RuntimeError) as error:
            if attempt == 2:
                raise
            stage(f"INFO: UI dump attempt {attempt + 1} failed ({type(error).__name__}); retrying a fresh compressed hierarchy")
            time.sleep(2)


def snapshot(name):
    (OUT / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    ET.ElementTree(ui()).write(OUT / f"{name}.xml", encoding="utf-8")


def find(label, tree=None, include_disabled=False):
    tree = tree if tree is not None else ui()
    parents = {child: parent for parent in tree.iter() for child in parent}
    def enabled(node):
        while node is not None:
            if node.get("enabled") == "false":
                return False
            node = parents.get(node)
        return True
    candidates = []
    for node in tree.iter("node"):
        values = [node.get("text", ""), node.get("content-desc", ""), node.get("resource-id", "")]
        if any(label.casefold() in value.casefold() for value in values) and (include_disabled or enabled(node)):
            bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
            if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                candidates.append((not any(label.casefold() == value.casefold() for value in values), bounds))
    return sorted(candidates)[0][1] if candidates else None


def tap(label, scroll=False):
    for _ in range(7 if scroll else 1):
        bounds = find(label)
        if bounds:
            adb("shell", "input", "tap", (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2)
            time.sleep(1)
            return
        if scroll:
            adb("shell", "input", "swipe", 360, 1000, 360, 420, 350)
            time.sleep(0.5)
    raise AssertionError(f"UI element absent: {label}")


def wait_for(check, description, seconds=60):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        if check():
            return
        time.sleep(2)
    raise AssertionError(f"Timed out: {description}")


def show_folder_section():
    # The folder box sits below the Telegram cards; scroll in small steps so it is not overshot.
    for _ in range(6):
        if find("Yozuvlar papkasini avtomatik topish", include_disabled=True):
            return
        adb("shell", "input", "swipe", 360, 900, 360, 650, 300)
        time.sleep(1)
    raise AssertionError("Recording folder section is not reachable")


def dismiss_test_alert():
    # React Native's default Alert is not cancelable with Android's Back button.
    wait_for(lambda: find("Sinov yuborildi"), "Send test confirmation dialog", 15)
    tap("OK")
    wait_for(lambda: not find("Sinov yuborildi"), "Send test confirmation dismissed", 15)


def scenario(mode):
    output = adb("shell", "am", "instrument", "-w", "-r", "-e", "mode", mode,
                 f"{APP}.test/{APP}.OperatorRuntimeInstrumentation", timeout=100)
    assert "INSTRUMENTATION_CODE: -1" in output and "FAIL:" not in output, output
    for line in output.splitlines():
        if "stream={" in line:
            return json.loads(line.split("stream=", 1)[1])
    raise AssertionError(output)


def launch():
    adb("shell", "am", "start", "-n", f"{APP}/.MainActivity")
    time.sleep(4)


def events():
    found = []
    for line in (OUT / "pos-events.jsonl").read_text(encoding="utf-8").splitlines(keepends=True):
        if not line.endswith("\n"):
            continue  # The live Node process may still be writing its last entry.
        if line.startswith("{"):
            found.append(json.loads(line))
    return found


def both_since(offset, predicate):
    return {entry["port"] for entry in events()[offset:] if predicate(entry)} == {8765, 8767}


def service_alive():
    text = adb("shell", "dumpsys", "activity", "services", APP)
    return "CallBridgeForegroundService" in text and "isForeground=true" in text


def boot_id():
    try:
        return adb("shell", "cat", "/proc/sys/kernel/random/boot_id", timeout=12, check=False).strip()
    except subprocess.TimeoutExpired:
        return ""


def app_recent_tasks():
    text = adb("shell", "dumpsys", "activity", "recents")
    # Task affinity is commonly A=10205:com.example.app, not A=com.example.app.
    blocks = re.findall(r"(?ms)^\s*\*?\s*Recent #\d+:.*?(?=^\s*\*?\s*Recent #\d+:|^\s*Visible recent tasks|\Z)", text)
    component = r"(?m)^\s*mActivityComponent=" + re.escape(APP) + r"/\S+\s*$"
    return text, [block for block in blocks if re.search(component, block)]


def own_app_root_task_id(block):
    component = re.search(r"(?m)^\s*mActivityComponent=(\S+)\s*$", block)
    task = re.search(r"(?m)^\s*taskId=(\d+)\s+rootTaskId=(\d+)\s*$", block)
    assert component and component[1].split("/", 1)[0] == APP, "Refusing to remove another app's task"
    assert task and task[1] == task[2] and int(task[1]) > 0, "Expected an isolated app root task"
    return int(task[1])


def gsm(action, number):
    output = adb("emu", "gsm", action, number)
    assert "OK" in output and "KO:" not in output, f"Emulator rejected gsm {action}: {output}"


def runtime_permission_state():
    package = adb("shell", "dumpsys", "package", APP)
    return {name: bool(re.search(r"android\.permission\." + name + r": granted=true\b", package))
            for name in ["READ_PHONE_STATE", "READ_CALL_LOG", "CAMERA", "POST_NOTIFICATIONS"]}


def runtime_permissions_granted():
    return all(runtime_permission_state().values())


def call(number, answer=True):
    offset = len(events())
    previous_ids = {e.get("recordId") for e in events() if e.get("type") == "call_record"}
    gsm("call", number)
    wait_for(lambda: both_since(offset, lambda e: e.get("type") == "call_start" and not e.get("test")), "call start at both POS")
    time.sleep(3)
    if answer:
        gsm("accept", number)
        time.sleep(4)
    gsm("cancel", number)
    wait_for(lambda: both_since(offset, lambda e: e.get("type") == "call_end" and not e.get("test")), "call end at both POS")
    outcome = "answered" if answer else "missed"
    def matching_records():
        return [e for e in events()[offset:] if e.get("type") == "call_record" and e.get("outcome") == outcome
                and e.get("recordId") and e["recordId"] not in previous_ids]
    def shared_record():
        records = matching_records()
        return any({e["port"] for e in records if e["recordId"] == record_id} == {8765, 8767}
                   for record_id in {e["recordId"] for e in records})
    wait_for(shared_record, f"same new {outcome} durable record at both POS", 90)
    if answer:
        records = matching_records()
        assert all(e.get("talkSeconds", 0) >= 1 for e in records), records
        assert any(e.get("named") for e in records), "Customer name was not resolved"
    stage(f"PASS: actual emulator {outcome} call reached both POS with final call-log records")


mock_log = (OUT / "pos-events.jsonl").open("w", encoding="utf-8")
mock = subprocess.Popen(["node", "source/scripts/operator-mock.mjs"], stdout=mock_log, stderr=subprocess.STDOUT)
try:
    assert adb("shell", "getprop", "ro.kernel.qemu").strip() == "1", "Emulator required"
    adb("shell", "wm", "size", "720x1280")
    adb("shell", "wm", "density", "320")
    adb("shell", "pm", "clear", APP)
    scenario("setup")
    for permission in ["READ_PHONE_STATE", "READ_CALL_LOG", "CAMERA", "POST_NOTIFICATIONS"]:
        adb("shell", "pm", "revoke", APP, f"android.permission.{permission}", check=False)
    launch()
    wait_for(lambda: find("Asosiy ruxsatlarni berish"), "Uzbek permissions onboarding")
    snapshot("01-permissions")
    tap("Asosiy ruxsatlarni berish")
    permission_requests = 1
    permission_dialogs = 0
    retry_permissions_at = time.monotonic() + 10
    deadline = time.monotonic() + 70
    while time.monotonic() < deadline:
        tree = ui()
        allowed = False
        for label in ["permission_allow_foreground_only_button", "permission_allow_button"]:
            bounds = find(label, tree)
            if bounds:
                permission_dialogs += 1
                ET.ElementTree(tree).write(OUT / f"permission-dialog-{permission_dialogs}.xml", encoding="utf-8")
                (OUT / f"permission-dialog-{permission_dialogs}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
                stage(f"INFO: permission dialog {permission_dialogs}, request {permission_requests}, pressing {label}; grants={json.dumps(runtime_permission_state())}")
                adb("shell", "input", "tap", (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2)
                time.sleep(1)
                retry_permissions_at = time.monotonic() + 10
                allowed = True
                break
        if not allowed and runtime_permissions_granted() and find("Tayyor", tree):
            break
        if not allowed and permission_requests < 3 and time.monotonic() >= retry_permissions_at and find("Asosiy ruxsatlarni berish", tree):
            stage(f"INFO: no permission dialog remains; retrying app grant button, request {permission_requests + 1}; grants={json.dumps(runtime_permission_state())}")
            tap("Asosiy ruxsatlarni berish")
            permission_requests += 1
            retry_permissions_at = time.monotonic() + 10
        time.sleep(1)
    assert runtime_permissions_granted(), "Onboarding did not grant all four requested runtime permissions"
    assert find("Tayyor"), "The onboarding completion button is still disabled"
    stage(f"INFO: all four Android runtime permissions confirmed after {permission_requests} app grant requests")
    tap("Tayyor")
    wait_for(lambda: both_since(0, lambda e: e.get("event") == "connected"), "two POS connections")
    wait_for(lambda: find("Ishlayapti"), "actual working status")
    snapshot("02-two-pos-home")
    offset = len(events())
    tap("Sinov yuborish")
    wait_for(lambda: both_since(offset, lambda e: e.get("type") == "call_start" and e.get("test")), "Send test reached both POS")
    dismiss_test_alert()
    time.sleep(3)
    stage("PASS: Uzbek onboarding, actual working status, two saved POS and Send test")

    call("998900001234")
    launch()
    before_recents, before_tasks = app_recent_tasks()
    (OUT / "recents-before-swipe.txt").write_text(before_recents, encoding="utf-8")
    assert before_tasks, "Cannot verify recents removal: the app task was absent before the swipe"
    # Remove the task through Android's recent-apps screen; keep its native service.
    adb("shell", "input", "keyevent", "KEYCODE_APP_SWITCH")
    time.sleep(2)
    snapshot("03-recents-before-swipe")
    removal_method = "overview_swipe"
    removed_task_ids = []
    for attempt in range(1, 4):
        # Pixel's first-use overview tooltip can consume the first gesture.
        # Retry only while Android still reports the actual application task.
        adb("shell", "input", "swipe", 360, 730, 360, 70, 400)
        time.sleep(3)
        recents, remaining_tasks = app_recent_tasks()
        (OUT / f"recents-after-swipe-{attempt}.txt").write_text(recents, encoding="utf-8")
        snapshot(f"03-recents-after-swipe-{attempt}")
        if not remaining_tasks:
            break
    if remaining_tasks:
        # Android 13/15 ActivityManagerShellCommand.runRootTaskRemove delegates
        # to removeTask(taskId), which exercises onTaskRemoved, not force-stop.
        removal_method = "am_stack_remove"
        removed_task_ids = [own_app_root_task_id(block) for block in remaining_tasks]
        stage(f"INFO: overview gestures left the task present; removing verified app task IDs {removed_task_ids} through Android ActivityManager")
        for task_id in removed_task_ids:
            adb("shell", "am", "stack", "remove", task_id)
        wait_for(lambda: not app_recent_tasks()[1], "verified app task removed by ActivityManager", 20)
        recents, remaining_tasks = app_recent_tasks()
        snapshot("03-recents-after-shell-removal")
    (OUT / "recents-after-swipe.txt").write_text(recents, encoding="utf-8")
    assert not remaining_tasks, "App task remains in Android's recent task list"
    (OUT / "task-removal-method.json").write_text(json.dumps({"method": removal_method, "taskIds": removed_task_ids, "confirmedAbsent": True}, indent=2), encoding="utf-8")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    assert service_alive(), "Foreground service stopped when app task was removed"
    call("998900001235", answer=False)
    stage(f"PASS: task removed through {removal_method}; foreground service and real call delivery continue")

    offset = len(events())
    previous_boot_id = boot_id()
    assert re.fullmatch(r"[0-9a-f-]{36}", previous_boot_id), "Could not identify the running Android boot"
    adb("reboot")
    adb("wait-for-device", timeout=160)
    # adb can briefly reconnect to the old daemon while reboot is being scheduled.
    wait_for(lambda: (current := boot_id()) != previous_boot_id and bool(re.fullmatch(r"[0-9a-f-]{36}", current)), "a new Android kernel boot", 180)
    wait_for(lambda: adb("shell", "getprop", "sys.boot_completed", timeout=15).strip() == "1", "normal reboot", 180)
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "input", "keyevent", "82")
    wait_for(lambda: "RUNNING_UNLOCKED" in adb("shell", "am", "get-started-user-state", "0", timeout=15), "first user unlock after reboot", 90)
    wait_for(lambda: both_since(offset, lambda e: e.get("event") == "connected"), "automatic two-POS reconnect after reboot and unlock", 90)
    wait_for(service_alive, "foreground service restored after reboot and first unlock", 90)
    (OUT / "reboot-evidence.json").write_text(json.dumps({"previousBootId": previous_boot_id, "currentBootId": boot_id(), "userState": adb("shell", "am", "get-started-user-state", "0").strip()}, indent=2), encoding="utf-8")
    # Proves these are live post-reboot peers, not late events from the old sockets.
    call("998900001236", answer=False)
    launch()
    wait_for(lambda: find("Ishlayapti"), "working UI after reboot")
    snapshot("04-after-reboot")
    stage("PASS: normal reboot and first unlock restore service and both saved POS without opening app")

    # With "All files access" the app finds Samsung's call-recording folder itself; no bot token is supplied.
    adb("shell", "mkdir", "-p", "/sdcard/Recordings/Call")
    adb("shell", "echo synthetic > /sdcard/Recordings/Call/sinov-qongiroq.m4a")
    adb("shell", "appops", "set", "--uid", APP, "MANAGE_EXTERNAL_STORAGE", "allow")
    tap("Telegram yozuvlari va hisobot", scroll=True)
    show_folder_section()
    wait_for(lambda: find("1 ta audio yozuv"), "automatically detected recording folder")
    wait_for(lambda: find("Ichki xotira/Recordings/Call"), "selected recording folder")
    tap("Sozlamalarni saqlash")
    # Saving deliberately disables this footer button, but its text is still evidence.
    wait_for(lambda: find("Saqlandi", include_disabled=True), "folder configuration saved in the fixed footer")
    snapshot("05-telegram-folder")
    # Recreate the application, proving that the folder is saved outside React state.
    adb("shell", "am", "force-stop", APP)
    launch()
    wait_for(lambda: find("Ishlayapti"), "explicit app reopen after force-stop")
    tap("Telegram yozuvlari va hisobot", scroll=True)
    # The saved folder must still be readable after process recreation.
    show_folder_section()
    wait_for(lambda: find("Ichki xotira/Recordings/Call"), "saved recording folder after app process restart")
    wait_for(lambda: find("1 ta audio yozuv"), "saved recording folder still readable after app process restart")
    tap("Orqaga")
    adb("shell", "input", "swipe", 360, 430, 360, 1110, 350)
    time.sleep(1)
    tap("Ikkinchi kassa POS ni o‘chirish")
    tap("O‘chirish")
    wait_for(lambda: not find("Ikkinchi kassa"), "POS removal")
    time.sleep(3)
    offset = len(events())
    tap("Sinov yuborish")
    time.sleep(4)
    ports = {e["port"] for e in events()[offset:] if e.get("type") == "call_start" and e.get("test")}
    assert ports == {8765}, f"Test after removal reached unexpected targets: {ports}"
    dismiss_test_alert()
    snapshot("06-one-pos-home")
    stage("PASS: recording folder auto-detected and saved; deleted POS receives no later tests")

    state = scenario("inspect")
    (OUT / "final-state.json").write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    assert state["targetCount"] == 1, state
    assert state["recordings"].get("folderUri", "").startswith("file://") and "Recordings/Call" in state["recordings"]["folderUri"], "Folder URI was not saved to native configuration"
    assert "Recordings/Call" in state["recordings"].get("folderName", ""), "Folder name was not saved"
    assert not state["recordings"].get("enabled"), "This synthetic test must keep Telegram audio disabled"
    assert len(state.get("periods", [])) >= 2, "Restart uptime periods not recorded"
    assert state["history"]["callCount"] >= 3, "Completed calls did not persist"
    stage("PASS: durable calls, saved target deletion and service uptime periods")
except BaseException:
    for name, command in [
        ("failure-activity-services.txt", ("shell", "dumpsys", "activity", "services", APP)),
        ("failure-user-state.txt", ("shell", "am", "get-started-user-state", "0")),
        ("failure-boot-id.txt", ("shell", "cat", "/proc/sys/kernel/random/boot_id")),
    ]:
        try:
            (OUT / name).write_text(adb(*command, check=False), encoding="utf-8")
        except BaseException:
            pass
    try:
        (OUT / "failure-package-permissions.txt").write_text(adb("shell", "dumpsys", "package", APP), encoding="utf-8")
    except BaseException:
        pass
    try:
        snapshot("failure")
    except BaseException:
        pass
    raise
finally:
    try:
        (OUT / "android-logcat.txt").write_text(adb("logcat", "-d", "-s", "AndroidRuntime:E", check=False), encoding="utf-8")
    finally:
        mock.terminate()
        try:
            mock.wait(timeout=10)
        except subprocess.TimeoutExpired:
            mock.kill()
            mock.wait(timeout=5)
        mock_log.close()
