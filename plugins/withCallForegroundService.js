/* eslint-disable @typescript-eslint/no-var-requires */
const { withAndroidManifest, withMainActivity, withMainApplication, withDangerousMod, withProjectBuildGradle, withAppBuildGradle } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

// Kotlin templates are the source of truth. Generated android/ may be recreated by Expo.
const PERMISSIONS = [
  'FOREGROUND_SERVICE', 'FOREGROUND_SERVICE_SPECIAL_USE', 'POST_NOTIFICATIONS',
  'RECEIVE_BOOT_COMPLETED', 'READ_PHONE_STATE', 'READ_CALL_LOG', 'INTERNET',
  'ACCESS_NETWORK_STATE', 'ACCESS_WIFI_STATE', 'WAKE_LOCK',
  'REQUEST_IGNORE_BATTERY_OPTIMIZATIONS', 'MANAGE_EXTERNAL_STORAGE',
  // Self-update from GitHub releases; Android 12+ installs our own update without a prompt.
  'REQUEST_INSTALL_PACKAGES', 'UPDATE_PACKAGES_WITHOUT_USER_ACTION',
  // Closed-hours replies, manager alerts and one-tap call back.
  'SEND_SMS', 'CALL_PHONE',
];

module.exports = function withCallForegroundService(config) {
  config = withAndroidManifest(config, cfg => {
    const manifest = cfg.modResults.manifest;
    manifest.$['xmlns:tools'] = 'http://schemas.android.com/tools';
    manifest['uses-permission'] ||= [];
    for (const permission of PERMISSIONS) {
      const name = `android.permission.${permission}`;
      if (!manifest['uses-permission'].some(p => p.$?.['android:name'] === name)) {
        manifest['uses-permission'].push({ $: { 'android:name': name } });
      }
    }
    // Android <= 10 uses the runtime storage permission instead of all-files access.
    for (const permission of ['READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE']) {
      const name = `android.permission.${permission}`;
      const existing = manifest['uses-permission'].find(p => p.$?.['android:name'] === name);
      if (existing) existing.$['android:maxSdkVersion'] = '29';
      else {
        manifest['uses-permission'].push({ $: { 'android:name': name, 'android:maxSdkVersion': '29' } });
      }
    }
    manifest['uses-permission'] = manifest['uses-permission'].filter(p => p.$?.['android:name'] !== 'android.permission.FOREGROUND_SERVICE_DATA_SYNC');
    const app = manifest.application[0];
    app.$['android:allowBackup'] = 'false';
    app.$['tools:replace'] = [...new Set((app.$['tools:replace'] || '').split(',').filter(Boolean).concat('android:allowBackup'))].join(',');
    app.$['android:requestLegacyExternalStorage'] = 'true';
    app.service = (app.service || []).filter(s => s.$?.['android:name'] !== '.CallBridgeForegroundService');
    app.service.push({
      $: { 'android:name': '.CallBridgeForegroundService', 'android:enabled': 'true', 'android:exported': 'false', 'android:stopWithTask': 'false', 'android:foregroundServiceType': 'specialUse' },
      property: [{ $: { 'android:name': 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE', 'android:value': 'User-enabled dedicated operator phone: continuously detect telephone calls, immediately relay caller events to paired local POS terminals, and deliver new phone-created call recordings to the configured private Telegram group.' } }],
    });
    app.receiver = (app.receiver || []).filter(r => !['.OperatorBootReceiver', '.OperatorUpdateReceiver'].includes(r.$?.['android:name']));
    app.receiver.push({ $: { 'android:name': '.OperatorUpdateReceiver', 'android:enabled': 'true', 'android:exported': 'false' } });
    app.receiver.push({
      $: { 'android:name': '.OperatorBootReceiver', 'android:enabled': 'true', 'android:exported': 'false' },
      'intent-filter': [{ action: [
        { $: { 'android:name': 'android.intent.action.BOOT_COMPLETED' } },
        { $: { 'android:name': 'android.intent.action.MY_PACKAGE_REPLACED' } },
      ] }],
    });
    return cfg;
  });
  config = withDangerousMod(config, ['android', async cfg => {
    const packageName = cfg.android?.package;
    if (!packageName) throw new Error('OperatorRuntime requires android.package');
    const javaDir = path.join(cfg.modRequest.platformProjectRoot, 'app/src/main/java', ...packageName.split('.'));
    fs.mkdirSync(javaDir, { recursive: true });
    const templates = path.join(__dirname, 'operator-native');
    for (const file of fs.readdirSync(templates).filter(name => name.endsWith('.kt'))) {
      fs.writeFileSync(path.join(javaDir, file), fs.readFileSync(path.join(templates, file), 'utf8').replaceAll('__PACKAGE__', packageName));
    }
    return cfg;
  }]);
  config = withMainApplication(config, cfg => {
    let source = cfg.modResults.contents;
    if (!source.includes('packages.add(OperatorRuntimePackage())')) {
      if (!source.includes('val packages = PackageList(this).packages')) throw new Error('OperatorRuntime: unsupported MainApplication template');
      source = source.replace('val packages = PackageList(this).packages', 'val packages = PackageList(this).packages\n            packages.add(OperatorRuntimePackage())');
    }
    cfg.modResults.contents = source;
    return cfg;
  });
  config = withMainActivity(config, cfg => {
    let source = cfg.modResults.contents;
    source = source.replace(/^.*ContextCompat\.startForegroundService\(this,.*CallBridgeForegroundService.*\r?\n/gm, '');
    if (!source.includes('OperatorRuntimeStore.startIfConfigured(this)')) {
      source = source.replace(/super\.onCreate\(([^)]*)\)/, match => `${match}\n    OperatorRuntimeStore.startIfConfigured(this)`);
    }
    cfg.modResults.contents = source;
    return cfg;
  });
  config = withAppBuildGradle(config, cfg => {
    const marker = '// OperatorRuntime: package only fully built ABIs.';
    if (!cfg.modResults.contents.includes(marker)) {
      cfg.modResults.contents += `\n${marker}\nandroid {\n    defaultConfig {\n        ndk {\n            abiFilters.clear()\n            abiFilters.addAll((findProperty('reactNativeArchitectures') ?: 'arm64-v8a,armeabi-v7a,x86,x86_64').split(',').collect { it.trim() }.findAll { it })\n        }\n    }\n}\n`;
    }
    return cfg;
  });
  return withProjectBuildGradle(config, cfg => {
    const marker = "exclude group: 'com.android.support'";
    if (!cfg.modResults.contents.includes(marker)) {
      cfg.modResults.contents += `\nallprojects { configurations.all { ${marker} } }\n`;
    }
    return cfg;
  });
};
