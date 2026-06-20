import { useEffect, useRef } from 'react';
import { Animated, Easing, StyleProp, ViewStyle } from 'react-native';

/**
 * Small reusable animation wrappers (all native-driver friendly) used to match
 * the motion in the Claude Design mockups: pulse, blink, spin, expanding rings,
 * loading dots and the QR scan line.
 */

function useLoopValue(
  build: (v: Animated.Value) => Animated.CompositeAnimation,
  deps: unknown[] = []
) {
  const value = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    const anim = build(value);
    anim.start();
    return () => anim.stop();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return value;
}

export function Pulse({
  children,
  min = 0.35,
  duration = 1400,
  style,
}: {
  children: React.ReactNode;
  min?: number;
  duration?: number;
  style?: StyleProp<ViewStyle>;
}) {
  const v = useLoopValue((value) =>
    Animated.loop(
      Animated.sequence([
        Animated.timing(value, { toValue: 1, duration: duration / 2, useNativeDriver: true }),
        Animated.timing(value, { toValue: 0, duration: duration / 2, useNativeDriver: true }),
      ])
    )
  );
  const opacity = v.interpolate({ inputRange: [0, 1], outputRange: [1, min] });
  return <Animated.View style={[style, { opacity }]}>{children}</Animated.View>;
}

export function Blink({
  children,
  min = 0.15,
  duration = 1000,
  style,
}: {
  children: React.ReactNode;
  min?: number;
  duration?: number;
  style?: StyleProp<ViewStyle>;
}) {
  return (
    <Pulse min={min} duration={duration} style={style}>
      {children}
    </Pulse>
  );
}

export function Spin({
  children,
  duration = 900,
  style,
}: {
  children: React.ReactNode;
  duration?: number;
  style?: StyleProp<ViewStyle>;
}) {
  const v = useLoopValue((value) =>
    Animated.loop(
      Animated.timing(value, {
        toValue: 1,
        duration,
        easing: Easing.linear,
        useNativeDriver: true,
      })
    )
  );
  const rotate = v.interpolate({ inputRange: [0, 1], outputRange: ['0deg', '360deg'] });
  return <Animated.View style={[style, { transform: [{ rotate }] }]}>{children}</Animated.View>;
}

/** Absolutely-positioned expanding ring. Place inside a relative parent. */
export function ExpandingRing({
  color,
  borderRadius = 999,
  duration = 2600,
  delay = 0,
}: {
  color: string;
  borderRadius?: number;
  duration?: number;
  delay?: number;
}) {
  const v = useLoopValue(
    (value) =>
      Animated.loop(
        Animated.sequence([
          Animated.delay(delay),
          Animated.timing(value, {
            toValue: 1,
            duration,
            easing: Easing.out(Easing.ease),
            useNativeDriver: true,
          }),
        ])
      ),
    [duration, delay]
  );
  const scale = v.interpolate({ inputRange: [0, 1], outputRange: [0.7, 2.2] });
  const opacity = v.interpolate({ inputRange: [0, 1], outputRange: [0.6, 0] });
  return (
    <Animated.View
      pointerEvents="none"
      style={{
        position: 'absolute',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        borderRadius,
        borderWidth: 1.5,
        borderColor: color,
        opacity,
        transform: [{ scale }],
      }}
    />
  );
}

export function Dots({ color, size = 7, gap = 6 }: { color: string; size?: number; gap?: number }) {
  return (
    <Animated.View style={{ flexDirection: 'row' }}>
      {[0, 1, 2].map((i) => (
        <Pulse key={i} min={0.2} duration={1200} style={{ marginLeft: i === 0 ? 0 : gap }}>
          <Animated.View
            style={{ width: size, height: size, borderRadius: size / 2, backgroundColor: color }}
          />
        </Pulse>
      ))}
    </Animated.View>
  );
}

/** Horizontal scan line that travels up and down inside `height`. */
export function ScanLine({
  color,
  travel,
  duration = 2600,
}: {
  color: string;
  travel: number;
  duration?: number;
}) {
  const v = useLoopValue(
    (value) =>
      Animated.loop(
        Animated.sequence([
          Animated.timing(value, {
            toValue: 1,
            duration: duration / 2,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
          Animated.timing(value, {
            toValue: 0,
            duration: duration / 2,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
        ])
      ),
    [travel, duration]
  );
  const translateY = v.interpolate({ inputRange: [0, 1], outputRange: [0, travel] });
  return (
    <Animated.View
      pointerEvents="none"
      style={{
        position: 'absolute',
        left: 8,
        right: 8,
        top: 8,
        height: 2,
        backgroundColor: color,
        shadowColor: color,
        shadowOpacity: 0.9,
        shadowRadius: 8,
        transform: [{ translateY }],
      }}
    />
  );
}
