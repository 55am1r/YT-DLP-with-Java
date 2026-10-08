// Turns the packed land mask (worldMaskData.js) into the character grid the ASCII map draws.
// Equirectangular: columns are equal slices of longitude, rows equal slices of latitude.
// The map is cut at 84°N and 58°S — nobody downloads from Antarctica, and it would take a
// fifth of the height.
import { MASK } from './worldMaskData.js'

export const LAT_TOP = 84
export const LAT_BOTTOM = -58

let bits = null

function decode() {
  if (bits) return bits
  const { w, h, rows } = MASK
  bits = new Uint8Array(w * h)
  rows.forEach((row, r) => {
    let x = 0
    let land = false
    for (const n of row.split('.')) {
      const len = parseInt(n, 36)
      if (land) bits.fill(1, r * w + x, r * w + x + len)
      x += len
      land = !land
    }
  })
  return bits
}

/**
 * Share of land (0–1) in each cell of a cols × rows grid, north to south. The caller picks
 * the threshold, so coastlines can be drawn lighter than interiors.
 */
export function landGrid(cols, rows, latTop = LAT_TOP, latBottom = LAT_BOTTOM) {
  const b = decode()
  const { w, h } = MASK
  const perLon = w / 360
  const perLat = h / 180
  const grid = []
  for (let r = 0; r < rows; r++) {
    const north = latTop - (r * (latTop - latBottom)) / rows
    const south = latTop - ((r + 1) * (latTop - latBottom)) / rows
    const y0 = Math.max(0, Math.floor((90 - north) * perLat))
    const y1 = Math.min(h, Math.max(y0 + 1, Math.ceil((90 - south) * perLat)))
    const line = new Float32Array(cols)
    for (let c = 0; c < cols; c++) {
      const x0 = Math.floor(((c * 360) / cols) * perLon)
      const x1 = Math.min(w, Math.max(x0 + 1, Math.ceil((((c + 1) * 360) / cols) * perLon)))
      let land = 0
      let n = 0
      for (let y = y0; y < y1; y++) {
        for (let x = x0; x < x1; x++) {
          n++
          land += b[y * w + x]
        }
      }
      line[c] = n ? land / n : 0
    }
    grid.push(line)
  }
  return grid
}

/** Is this coordinate on land? Reads the 0.5° mask directly (720×360, 90°N down). */
export function isLand(lat, lon) {
  const b = decode()
  const { w, h } = MASK
  const x = Math.min(w - 1, Math.max(0, Math.floor(((lon + 180) / 360) * w)))
  const y = Math.min(h - 1, Math.max(0, Math.floor(((90 - lat) / 180) * h)))
  return b[y * w + x] === 1
}

/** The grid cell a coordinate falls in; points beyond the cut-off latitudes sit on the edge row. */
export function cellOf(lat, lon, cols, rows, latTop = LAT_TOP, latBottom = LAT_BOTTOM) {
  const r = Math.floor(((latTop - lat) / (latTop - latBottom)) * rows)
  const c = Math.floor(((lon + 180) / 360) * cols)
  return {
    r: Math.min(rows - 1, Math.max(0, r)),
    c: Math.min(cols - 1, Math.max(0, c)),
  }
}
