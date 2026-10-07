// Sanity check for the ASCII map's land grid: run `node scripts/check-world-mask.mjs`.
// Exits non-zero if the data decodes wrongly or the world comes out in the wrong place.
import { MASK } from '../src/admin/worldMaskData.js'
import { cellOf, landGrid } from '../src/admin/worldMask.js'

let failures = 0
const fail = (msg) => { failures++; console.error('FAIL', msg) }

MASK.rows.forEach((row, r) => {
  const width = row.split('.').reduce((sum, n) => sum + parseInt(n, 36), 0)
  if (width !== MASK.w) fail(`row ${r} decodes to ${width} cells, not ${MASK.w}`)
})

const cols = 100
const rows = 24
const grid = landGrid(cols, rows)
const share = (lat, lon) => {
  const { r, c } = cellOf(lat, lon, cols, rows)
  return grid[r][c]
}

const inland = { Hyderabad: [17.385, 78.487], Madrid: [40.417, -3.704], Chicago: [41.878, -87.63],
  Moscow: [55.756, 37.617], Nairobi: [-1.286, 36.817], Brasilia: [-15.794, -47.882], 'Alice Springs': [-23.698, 133.881] }
for (const [name, [lat, lon]] of Object.entries(inland)) {
  if (share(lat, lon) < 0.5) fail(`${name} should be on land, share ${share(lat, lon).toFixed(2)}`)
}
const ocean = { 'mid-Pacific': [0, -150], 'mid-Atlantic': [30, -40], 'Indian Ocean': [-30, 80] }
for (const [name, [lat, lon]] of Object.entries(ocean)) {
  if (share(lat, lon) > 0) fail(`${name} should be sea, share ${share(lat, lon).toFixed(2)}`)
}
const edge = cellOf(-80, 200, cols, rows)
if (edge.r !== rows - 1 || edge.c !== cols - 1) fail(`out-of-range points should clamp to the edge, got ${edge.r},${edge.c}`)

if (failures) process.exit(1)
console.log(`world mask OK — ${MASK.w}×${MASK.h}, ${Object.keys(inland).length} cities on land, ${Object.keys(ocean).length} oceans empty`)
