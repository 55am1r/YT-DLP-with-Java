// The geometry behind the dotted globe, kept free of three.js so it can be unit-checked in
// plain Node (scripts/check-globe.mjs). Globe.jsx turns these plain numbers into three objects.
import { isLand, LAT_BOTTOM, LAT_TOP } from './worldMask.js'

const DEG = Math.PI / 180

/**
 * A point on a sphere of radius r for a latitude/longitude, as [x, y, z]. +Y is the north pole;
 * the exact spin around Y doesn't matter as long as the dots and the pins use this same mapping,
 * so every pin lands on its own continent.
 */
export function latLonToVec3(lat, lon, r = 1) {
  const phi = (90 - lat) * DEG
  const theta = (lon + 180) * DEG
  return [-r * Math.sin(phi) * Math.cos(theta), r * Math.cos(phi), r * Math.sin(phi) * Math.sin(theta)]
}

/**
 * The land points that make the dotted continents. A plain lat/lon grid would bunch up near the
 * poles, so each latitude ring takes fewer longitude samples by 1/cos(lat) — the dots then sit
 * at roughly even spacing all over the sphere. Antarctica is left off (same cut as the flat map).
 */
export function landDots(stepDeg = 1.7, latTop = LAT_TOP, latBottom = LAT_BOTTOM) {
  const dots = []
  for (let lat = latBottom; lat <= latTop; lat += stepDeg) {
    const lonStep = stepDeg / Math.max(Math.cos(lat * DEG), 0.12)
    for (let lon = -180; lon < 180; lon += lonStep) {
      if (isLand(lat, lon)) dots.push([lat, lon])
    }
  }
  return dots
}

/** Flat [x,y,z, x,y,z, …] land-dot positions on a sphere of radius r — ready for a THREE buffer. */
export function dotPositions(r = 1, stepDeg = 1.7) {
  const dots = landDots(stepDeg)
  const out = new Float32Array(dots.length * 3)
  dots.forEach(([lat, lon], i) => {
    const [x, y, z] = latLonToVec3(lat, lon, r)
    out[i * 3] = x
    out[i * 3 + 1] = y
    out[i * 3 + 2] = z
  })
  return out
}
