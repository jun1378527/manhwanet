import { CHECK_FILL, COVER_PALETTES, STAR_FILL, STAR_STROKE, STATIC_VIEWBOX, staticLayout } from "@/lib/home-banner/bodies";
import type { BannerSlideId } from "@/lib/home-banner/slides";

function starPoints(r: number): string {
  const pts: string[] = [];
  for (let i = 0; i < 10; i++) {
    const a = -Math.PI / 2 + (i * Math.PI) / 5;
    const d = i % 2 ? r * 0.46 : r;
    pts.push(`${(Math.cos(a) * d).toFixed(2)},${(Math.sin(a) * d).toFixed(2)}`);
  }
  return pts.join(" ");
}

const deg = (rad: number) => (rad * 180) / Math.PI;

/**
 * 물리 엔진 없이 보여 주는 배너 장식(움직임 줄이기, 엔진 로딩 중·실패). 장식이므로 보조 기술에서 숨긴다.
 */
export function BannerStaticArt({ slide }: { slide: BannerSlideId }) {
  const items = staticLayout(slide);
  return (
    <svg
      className="home-banner__static-art"
      viewBox={`0 0 ${STATIC_VIEWBOX.width} ${STATIC_VIEWBOX.height}`}
      preserveAspectRatio="xMidYMax meet"
      aria-hidden="true"
      focusable="false"
    >
      <defs>
        {COVER_PALETTES.map(([top, bottom], i) => (
          <linearGradient key={i} id={`banner-cover-${i}`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor={top} />
            <stop offset="1" stopColor={bottom} />
          </linearGradient>
        ))}
      </defs>
      {items.map((item, i) => {
        switch (item.kind) {
          case "cover":
            return (
              <g key={i} transform={`translate(${item.x} ${item.y}) rotate(${deg(item.angle)})`}>
                <rect x={-item.w / 2} y={-item.h / 2} width={item.w} height={item.h} rx="5" fill={`url(#banner-cover-${item.palette})`} />
                <circle cx={item.w * 0.12} cy={-item.h * 0.14} r={item.w * 0.2} fill={COVER_PALETTES[item.palette][2]} opacity="0.85" />
                <rect x={-item.w / 2 + 6} y={item.h / 2 - 17} width={item.w * 0.55} height="4" fill="rgba(255,255,255,.75)" />
                <rect x={-item.w / 2 + 6} y={item.h / 2 - 10} width={item.w * 0.35} height="4" fill="rgba(255,255,255,.75)" />
              </g>
            );
          case "star":
            return (
              <polygon
                key={i}
                points={starPoints(item.r * 1.15)}
                transform={`translate(${item.x} ${item.y}) rotate(${deg(item.angle)})`}
                fill={STAR_FILL}
                stroke={STAR_STROKE}
                strokeWidth="1.5"
              />
            );
          case "chip":
            return (
              <g key={i} transform={`translate(${item.x} ${item.y})`}>
                <rect x={-item.w / 2} y={-item.h / 2} width={item.w} height={item.h} rx={item.h / 2}
                  fill={item.highlight ? STAR_FILL : "#2a2e37"} stroke={item.highlight ? STAR_FILL : "rgba(255,255,255,.18)"} />
                <text textAnchor="middle" dominantBaseline="central" fontSize="12.5" fontWeight="700" fill={item.highlight ? "#15171c" : "#f2f3f5"}>
                  {item.label}
                </text>
              </g>
            );
          case "block":
            return (
              <g key={i} transform={`translate(${item.x} ${item.y})`}>
                <rect x={-item.w / 2} y={-item.h / 2} width={item.w} height={item.h} rx="12" fill={item.color} />
                <circle cx={-item.w / 2 + 20} cy="0" r="10" fill="#123b2c" />
                <text x={-item.w / 2 + 20} textAnchor="middle" dominantBaseline="central" fontSize="11" fontWeight="800" fill={item.color}>
                  {item.step}
                </text>
                <text x={-item.w / 2 + 38} dominantBaseline="central" fontSize="15" fontWeight="800" fill="#123b2c">
                  {item.label}
                </text>
              </g>
            );
          case "check":
            return (
              <g key={i} transform={`translate(${item.x} ${item.y})`}>
                <circle r={item.r} fill={CHECK_FILL} />
                <path
                  d={`M${-item.r * 0.42} 0 L${-item.r * 0.1} ${item.r * 0.32} L${item.r * 0.45} ${-item.r * 0.3}`}
                  stroke="#0f3d2e" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" fill="none"
                />
              </g>
            );
        }
      })}
    </svg>
  );
}
