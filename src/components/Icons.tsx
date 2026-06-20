import Svg, { Circle, Path, Rect } from 'react-native-svg';

import { colors } from '../theme';

/**
 * Icon set translated from the Claude Design mockups. Each icon is a thin
 * wrapper over react-native-svg so it scales and recolors cleanly.
 */

export interface IconProps {
  size?: number;
  color?: string;
}

const S = (size: number) => ({ width: size, height: size, viewBox: '0 0 24 24' });

const stroke = (color: string, w = 2) => ({
  stroke: color,
  strokeWidth: w,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  fill: 'none' as const,
});

/** App brand handset (filled). */
export function PhoneFill({ size = 24, color = colors.onBrand }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Path
        d="M6.5 4h3l1.5 4-2 1.5a12 12 0 0 0 5.5 5.5l1.5-2 4 1.5v3a2 2 0 0 1-2 2A15 15 0 0 1 4.5 6a2 2 0 0 1 2-2Z"
        fill={color}
      />
    </Svg>
  );
}

export function Phone({ size = 24, color = colors.muted2 }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Path
        d="M6.5 4h3l1.5 4-2 1.5a12 12 0 0 0 5.5 5.5l1.5-2 4 1.5v3a2 2 0 0 1-2 2A15 15 0 0 1 4.5 6a2 2 0 0 1 2-2Z"
        {...stroke(color)}
      />
    </Svg>
  );
}

export function PhoneOff({ size = 24, color = colors.danger }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Path d="M3 3l18 18" {...stroke(color, 2.2)} />
      <Path
        d="M6.5 4h3l1.5 4-2 1.5a12 12 0 0 0 5.5 5.5l1.5-2 4 1.5v3a2 2 0 0 1-2 2 15 15 0 0 1-2-.2"
        {...stroke(color)}
      />
    </Svg>
  );
}

export function Camera({ size = 24, color = colors.muted }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Rect x="3" y="6" width="18" height="13" rx="3" {...stroke(color)} />
      <Circle cx="12" cy="12.5" r="3.2" {...stroke(color)} />
      <Path d="M8 6l1.2-2h5.6L16 6" {...stroke(color)} />
    </Svg>
  );
}

export function CameraOff({ size = 24, color = colors.danger }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Rect x="3" y="6" width="18" height="13" rx="3" {...stroke(color)} />
      <Circle cx="12" cy="12.5" r="3.2" {...stroke(color)} />
      <Path d="M8 6l1.2-2h5.6L16 6" {...stroke(color)} />
      <Path d="M3 3l18 18" {...stroke(color, 2.2)} />
    </Svg>
  );
}

export function CallLog({ size = 24, color = colors.muted }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Path d="M5 5h14M5 10h14M5 15h9" {...stroke(color)} />
      <Circle cx="17.5" cy="16" r="3.3" {...stroke(color)} />
      <Path d="M17.5 14.5V16l1 .8" {...stroke(color, 1.6)} />
    </Svg>
  );
}

export function Bell({ size = 24, color = colors.muted }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Path d="M6 9a6 6 0 0 1 12 0c0 5 2 6 2 6H4s2-1 2-6Z" {...stroke(color)} />
      <Path d="M10 19a2 2 0 0 0 4 0" {...stroke(color)} />
    </Svg>
  );
}

/** Filled tinted disc with a check — granted state. */
export function CheckCircle({ size = 22, color = colors.connected }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Circle cx="12" cy="12" r="10" fill="rgba(54,192,126,0.16)" />
      <Path d="M8 12.5l2.5 2.5L16 9" {...stroke(color, 2.2)} />
    </Svg>
  );
}

/** Outline ring with a check — used inside status cards. */
export function CheckRing({ size = 24, color = colors.connected }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Circle cx="12" cy="12" r="9" {...stroke(color)} />
      <Path d="M9 12.5l2 2 4-4.5" {...stroke(color, 2.4)} />
    </Svg>
  );
}

export function XCircle({ size = 22, color = colors.muted2 }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Circle cx="12" cy="12" r="9.5" {...stroke(color, 1.6)} />
      <Path d="M9 9l6 6M15 9l-6 6" {...stroke(color)} />
    </Svg>
  );
}

export function Warning({ size = 22, color = colors.warn }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Path
        d="M10.3 3.2 2.5 17a2 2 0 0 0 1.7 3h15.6a2 2 0 0 0 1.7-3L13.7 3.2a2 2 0 0 0-3.4 0Z"
        {...stroke(color)}
      />
      <Path d="M12 9v4.5M12 17h.01" {...stroke(color)} />
    </Svg>
  );
}

export function Star({ size = 18, color = colors.connected }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Path
        d="M12 3l1.6 3.7 4 .4-3 2.7.9 3.9L12 11.8 8.5 13.7l.9-3.9-3-2.7 4-.4L12 3Z"
        fill={color}
      />
    </Svg>
  );
}

export function ArrowIncoming({ size = 16, color = colors.connected }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Path d="M17 7L9 15M9 15H15M9 15V9" {...stroke(color, 2.2)} />
    </Svg>
  );
}

export function ArrowOutgoing({ size = 16, color = colors.outgoing }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Path d="M7 17L15 9M15 9H9M15 9V15" {...stroke(color, 2.2)} />
    </Svg>
  );
}

export function CallEnded({ size = 16, color = colors.muted2 }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Path
        d="M4.5 14.5l2-2a2 2 0 0 1 2.4-.3l1.6.9a2 2 0 0 0 2.4-.3l3.7-3.7a2 2 0 0 0-.3-2.4l-.9-1.6a2 2 0 0 1 .3-2.4l2-2"
        {...stroke(color)}
      />
      <Path d="M20 16l-2.5 2.5" {...stroke(color)} />
    </Svg>
  );
}

export function Send({ size = 17, color = colors.onBrand }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Path d="M21 3L10 14M21 3l-7 18-4-7-7-4 18-7Z" {...stroke(color)} />
    </Svg>
  );
}

export function Rescan({ size = 16, color = colors.textSoft }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Path d="M4 9V5h4M20 9V5h-4M4 15v4h4M20 15v4h-4" {...stroke(color)} />
    </Svg>
  );
}

export function Copy({ size = 15, color = colors.muted }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Rect x="8" y="8" width="12" height="12" rx="2.5" {...stroke(color)} />
      <Path
        d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"
        {...stroke(color)}
      />
    </Svg>
  );
}

export function Globe({ size = 12, color = colors.muted }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Circle cx="12" cy="12" r="9" {...stroke(color)} />
      <Path
        d="M3 12h18M12 3c2.5 2.5 2.5 15.5 0 18M12 3c-2.5 2.5-2.5 15.5 0 18"
        {...stroke(color, 1.6)}
      />
    </Svg>
  );
}

export function DotsVertical({ size = 22, color = colors.muted }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Circle cx="12" cy="5" r="1.6" fill={color} />
      <Circle cx="12" cy="12" r="1.6" fill={color} />
      <Circle cx="12" cy="19" r="1.6" fill={color} />
    </Svg>
  );
}

export function Settings({ size = 17, color = colors.text }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Circle cx="12" cy="12" r="3" {...stroke(color)} />
      <Path
        d="M19 12a7 7 0 0 0-.1-1l2-1.6-2-3.4-2.3 1a7 7 0 0 0-1.7-1l-.3-2.5h-4l-.3 2.5a7 7 0 0 0-1.7 1l-2.3-1-2 3.4 2 1.6a7 7 0 0 0 0 2l-2 1.6 2 3.4 2.3-1a7 7 0 0 0 1.7 1l.3 2.5h4l.3-2.5a7 7 0 0 0 1.7-1l2.3 1 2-3.4-2-1.6a7 7 0 0 0 .1-1Z"
        {...stroke(color, 1.6)}
      />
    </Svg>
  );
}

export function Info({ size = 18, color = colors.muted }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Circle cx="12" cy="12" r="9" {...stroke(color)} />
      <Path d="M12 11v5M12 8h.01" {...stroke(color)} />
    </Svg>
  );
}

export function Clock({ size = 13, color = colors.brand }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Circle cx="12" cy="12" r="9" {...stroke(color)} />
      <Path d="M12 7.5V12l3 2" {...stroke(color)} />
    </Svg>
  );
}

export function Database({ size = 18, color = colors.warn }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Path d="M4 7c0-1.5 3.6-2.5 8-2.5S20 5.5 20 7s-3.6 2.5-8 2.5S4 8.5 4 7Z" {...stroke(color, 1.8)} />
      <Path
        d="M4 7v10c0 1.5 3.6 2.5 8 2.5s8-1 8-2.5V7M4 12c0 1.5 3.6 2.5 8 2.5s8-1 8-2.5"
        {...stroke(color, 1.8)}
      />
    </Svg>
  );
}

export function ArrowRight({ size = 18, color = colors.onBrand }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Path d="M5 12h14M13 6l6 6-6 6" {...stroke(color, 2.2)} />
    </Svg>
  );
}

export function SpinnerArc({ size = 24, color = colors.warn }: IconProps) {
  return (
    <Svg {...S(size)}>
      <Path d="M12 3a9 9 0 1 0 9 9" {...stroke(color, 2.4)} />
    </Svg>
  );
}
