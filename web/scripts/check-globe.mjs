// Sanity check for the globe's geometry: run `node scripts/check-globe.mjs`.
// Exits non-zero if the land dots or the lat/lon → sphere mapping come out wrong.
import { isLand } from '../src/admin/worldMask.js'
import { dotPositions, landDots, latLonToVec3 } from '../src/admin/globeGeo.js'

let failures = 0
const fail = (msg) => { failures++; console.error('FAIL', msg) }
const near = (a, b, eps = 1e-6) => Math.abs(a - b) < eps

// Known land and sea, straight from the mask.
const land = { Hyderabad: [17.385, 78.487], Madrid: [40.417, -3.704], Chicago: [41.878, -87.63],
  Nairobi: [-1.286, 36.817], Brasilia: [-15.794, -47.882], Canberra: [-35.282, 149.129] }
for (const [name, [lat, lon]] of Object.entries(land)) {
  if (!isLand(lat, lon)) fail(`${name} should be on land`)
}
const sea = { 'mid-Pacific': [0, -150], 'mid-Atlantic': [30, -40], 'Indian Ocean': [-30, 80] }
for (const [name, [lat, lon]] of Object.entries(sea)) {
  if (isLand(lat, lon)) fail(`${name} should be sea`)
}

// Every point sits on the sphere of the radius asked for.
const R = 1.0
for (const [lat, lon] of [[0, 0], [45, -90], [-33, 151], [84, 10], [-58, -70]]) {
  const [x, y, z] = latLonToVec3(lat, lon, R)
  const len = Math.hypot(x, y, z)
  if (!near(len, R, 1e-5)) fail(`(${lat},${lon}) is ${len.toFixed(4)} from centre, not ${R}`)
}

// The north pole is straight up (+Y); the mapping is one-to-one, not collapsed.
const [, ny] = latLonToVec3(90, 0, R)
if (!near(ny, R, 1e-5)) fail(`north pole should be at +Y=${R}, got ${ny.toFixed(4)}`)
const a = latLonToVec3(10, 20, R)
const b = latLonToVec3(10, 200, R) // 180° apart in longitude → opposite side
if (near(a[0], b[0]) && near(a[2], b[2])) fail('antipodal longitudes map to the same point')

// Enough dots to read as continents, every one of them on land, none inside the sphere.
const dots = landDots(1.7)
if (dots.length < 3000 || dots.length > 60000) fail(`land dot count ${dots.length} is outside a sane range`)
if (!dots.every(([lat, lon]) => isLand(lat, lon))) fail('a land dot fell on sea')
const pos = dotPositions(R, 1.7)
if (pos.length !== dots.length * 3) fail(`dotPositions has ${pos.length} numbers, not ${dots.length * 3}`)
for (let i = 0; i < pos.length; i += 3) {
  if (!near(Math.hypot(pos[i], pos[i + 1], pos[i + 2]), R, 1e-4)) { fail('a dot is off the sphere'); break }
}

if (failures) process.exit(1)
console.log(`globe geometry OK — ${dots.length} land dots, all on the sphere and on land`)
