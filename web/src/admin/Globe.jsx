// The dotted 3-D globe on the Overview. Continents are drawn as thousands of small dots taken
// from the same land mask the old ASCII map used (so it works fully offline — no map tiles), and
// every device becomes a location pin at its lat/lon. Online devices glow and pulse; clicking a
// pin spins the globe to it and opens that device. Drag to orbit 360°, scroll to zoom, right-drag
// to pan; it auto-spins when you leave it alone. Colours come from EZ-Tube's own CSS tokens, so it
// follows day/night mode.
//
// three.js only ever ships inside the admin chunk, so teammates never download it.
import { useEffect, useRef, useState } from 'react'
import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { dotPositions, latLonToVec3 } from './globeGeo'
import { SOURCE, timeAgo } from './format'

const DAY = 86_400_000
const R = 1
const AUTO_RESUME_MS = 3500 // start spinning again this long after you stop touching it

const FILTERS = [
  { id: 'online', label: 'Online now', test: (d) => d.online },
  { id: 'day', label: '24 hours', test: (d, now) => now - d.lastSeen < DAY },
  { id: 'week', label: '7 days', test: (d, now) => now - d.lastSeen < 7 * DAY },
  { id: 'all', label: 'All time', test: () => true },
]

/** Read EZ-Tube's colours off the live stylesheet and mix the few shades the globe needs. */
function readColors(el) {
  const cs = getComputedStyle(el)
  const col = (name, fallback) => {
    const c = new THREE.Color(fallback)
    try { c.setStyle(cs.getPropertyValue(name).trim() || fallback) } catch { /* keep fallback */ }
    return c
  }
  const accent = col('--accent', '#e11d2a')
  const text = col('--text', '#eef1f7')
  const bg = col('--bg', '#0e0f13')
  const surface = col('--surface', '#191c24')
  const ocean = surface.clone().lerp(bg, 0.5)
  return {
    ocean,
    dot: text.clone().lerp(ocean, 0.52), // muted land dots that still read against the ocean
    pin: accent.clone(),
    online: accent.clone().lerp(new THREE.Color('#ffffff'), 0.32), // a hotter red for live pins
    atmosphere: accent.clone(),
  }
}

function canvas(w, h) {
  const cv = document.createElement('canvas')
  cv.width = w
  cv.height = h
  return cv
}

/** A soft round dot for the continents (tinted white — PointsMaterial colours it). */
function dotTexture() {
  const s = 64
  const cv = canvas(s, s)
  const g = cv.getContext('2d')
  const grad = g.createRadialGradient(s / 2, s / 2, 0, s / 2, s / 2, s / 2)
  grad.addColorStop(0, 'rgba(255,255,255,1)')
  grad.addColorStop(0.6, 'rgba(255,255,255,0.95)')
  grad.addColorStop(1, 'rgba(255,255,255,0)')
  g.fillStyle = grad
  g.fillRect(0, 0, s, s)
  const t = new THREE.CanvasTexture(cv)
  t.needsUpdate = true
  return t
}

/** A classic ring-topped location pin, tip at the bottom, in the given colour. */
function pinTexture(cssColor) {
  const w = 48
  const h = 64
  const cv = canvas(w, h)
  const g = cv.getContext('2d')
  const cx = w / 2
  const cy = w * 0.42
  const r = w * 0.34
  g.fillStyle = cssColor
  g.shadowColor = cssColor
  g.shadowBlur = 8
  g.beginPath() // the point
  g.moveTo(cx - r * 0.66, cy + r * 0.5)
  g.lineTo(cx + r * 0.66, cy + r * 0.5)
  g.lineTo(cx, h - 3)
  g.closePath()
  g.fill()
  g.beginPath() // the head
  g.arc(cx, cy, r, 0, Math.PI * 2)
  g.fill()
  g.shadowBlur = 0
  g.globalCompositeOperation = 'destination-out' // punch the ring hole so the globe shows through
  g.beginPath()
  g.arc(cx, cy, r * 0.44, 0, Math.PI * 2)
  g.fill()
  g.globalCompositeOperation = 'source-over'
  const t = new THREE.CanvasTexture(cv)
  t.needsUpdate = true
  return t
}

/** The pulsing halo under a live pin (tinted by the sprite's colour). */
function haloTexture() {
  const s = 96
  const cv = canvas(s, s)
  const g = cv.getContext('2d')
  const grad = g.createRadialGradient(s / 2, s / 2, 0, s / 2, s / 2, s / 2)
  grad.addColorStop(0, 'rgba(255,255,255,0.0)')
  grad.addColorStop(0.55, 'rgba(255,255,255,0.0)')
  grad.addColorStop(0.72, 'rgba(255,255,255,0.9)')
  grad.addColorStop(1, 'rgba(255,255,255,0.0)')
  g.fillStyle = grad
  g.fillRect(0, 0, s, s)
  const t = new THREE.CanvasTexture(cv)
  t.needsUpdate = true
  return t
}

export default function Globe({ devices, selectedId, onSelect }) {
  const mount = useRef(null)
  const tipEl = useRef(null)
  const tipPos = useRef({ x: 0, y: 0 }) // last pointer spot, so the tooltip is placed right on its first frame
  const kit = useRef(null) // all the three.js objects, built once
  const flyTo = useRef(() => {})
  const [filter, setFilter] = useState('all')
  const [hover, setHover] = useState(null) // the device under the pointer, for the tooltip
  const [failed, setFailed] = useState(false)
  const [skin, setSkin] = useState(0) // bumped when the theme changes, to recolour

  const now = Date.now()
  const test = FILTERS.find((f) => f.id === filter).test
  const placed = devices.filter((d) => d.place && test(d, now))
  const placedKey = placed.map((d) => `${d.id}:${d.online ? 1 : 0}`).join(',')
  const withoutPlace = devices.filter((d) => test(d, now) && !d.place).length

  // ---- build the scene once --------------------------------------------------------------
  useEffect(() => {
    const host = mount.current
    if (!host) return undefined
    let renderer
    try {
      renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
    } catch {
      setFailed(true)
      return undefined
    }
    const width = host.clientWidth || 600
    const height = host.clientHeight || 420
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
    renderer.setSize(width, height)
    host.appendChild(renderer.domElement)

    const scene = new THREE.Scene()
    const camera = new THREE.PerspectiveCamera(42, width / height, 0.1, 100)
    const [ix, iy, iz] = latLonToVec3(18, 79, 2.5) // open looking at India — where the team is, filling the frame
    camera.position.set(ix, iy, iz)

    const colors = readColors(host)
    const globeGroup = new THREE.Group()
    scene.add(globeGroup)

    const ocean = new THREE.Mesh(
      new THREE.SphereGeometry(R * 0.985, 64, 48),
      new THREE.MeshBasicMaterial({ color: colors.ocean }),
    )
    globeGroup.add(ocean)

    const atmosphere = new THREE.Mesh(
      new THREE.SphereGeometry(R * 1.14, 48, 32),
      new THREE.MeshBasicMaterial({
        color: colors.atmosphere, transparent: true, opacity: 0.05,
        side: THREE.BackSide, blending: THREE.AdditiveBlending, depthWrite: false,
      }),
    )
    globeGroup.add(atmosphere)

    const dotTex = dotTexture()
    const dotGeo = new THREE.BufferGeometry()
    dotGeo.setAttribute('position', new THREE.BufferAttribute(dotPositions(R * 1.002, 1.3), 3))
    const dots = new THREE.Points(dotGeo, new THREE.PointsMaterial({
      size: 0.02, map: dotTex, color: colors.dot, transparent: true, depthWrite: false, sizeAttenuation: true,
    }))
    globeGroup.add(dots)

    const pinsGroup = new THREE.Group()
    globeGroup.add(pinsGroup)

    const controls = new OrbitControls(camera, renderer.domElement)
    controls.enableDamping = true
    controls.dampingFactor = 0.08
    controls.rotateSpeed = 0.6
    controls.panSpeed = 0.5
    controls.zoomSpeed = 0.8
    controls.minDistance = 1.55
    controls.maxDistance = 6
    controls.autoRotate = true
    controls.autoRotateSpeed = 0.45

    // Pause the spin while the pointer is on it; pick it back up a few seconds after it's let go.
    let resumeAt = 0
    controls.addEventListener('start', () => { controls.autoRotate = false; resumeAt = 0 })
    controls.addEventListener('end', () => { resumeAt = performance.now() + AUTO_RESUME_MS })

    const raycaster = new THREE.Raycaster()
    const pointer = new THREE.Vector2()

    // Fly the globe so a lat/lon faces the camera, keeping the current zoom. We move the camera
    // along a great circle to sit over the pin's direction — no OrbitControls setters needed, so
    // it works across three versions.
    let fly = null // the target camera direction (unit vector) while a fly is in progress
    flyTo.current = (place) => {
      if (!place) return
      const [vx, vy, vz] = latLonToVec3(place.lat, place.lon, 1)
      fly = new THREE.Vector3(vx, vy, vz).normalize()
      controls.autoRotate = false
      resumeAt = 0
    }

    const start = performance.now()
    let raf = 0
    const tick = () => {
      raf = requestAnimationFrame(tick)
      const t = (performance.now() - start) / 1000

      if (fly) {
        const dir = camera.position.clone().normalize()
        if (dir.angleTo(fly) < 0.02) {
          fly = null
          resumeAt = performance.now() + AUTO_RESUME_MS * 1.5
        } else {
          const dist = camera.position.length()
          camera.position.copy(dir.lerp(fly, 0.15).normalize().multiplyScalar(dist))
        }
      } else if (resumeAt && performance.now() > resumeAt) {
        controls.autoRotate = true
        resumeAt = 0
      }

      // Hide pins (and their halos) on the far side so only the facing hemisphere shows.
      const camDir = camera.position.clone().normalize()
      for (const pin of pinsGroup.children) {
        if (pin.userData.dir) {
          const front = pin.userData.dir.dot(camDir) > 0.04
          pin.visible = front
          if (pin.userData.halo) {
            pin.userData.halo.visible = front
            const pulse = 1 + 0.35 * Math.sin(t * 3 + pin.userData.phase)
            const base = pin.userData.haloBase
            pin.userData.halo.scale.set(base * pulse, base * pulse, 1)
            pin.userData.halo.material.opacity = 0.35 + 0.3 * (0.5 + 0.5 * Math.sin(t * 3 + pin.userData.phase))
          }
        }
      }

      controls.update()
      renderer.render(scene, camera)
    }
    tick()

    function pickDevice(ev) {
      const rect = renderer.domElement.getBoundingClientRect()
      pointer.x = ((ev.clientX - rect.left) / rect.width) * 2 - 1
      pointer.y = -((ev.clientY - rect.top) / rect.height) * 2 + 1
      raycaster.setFromCamera(pointer, camera)
      const pins = pinsGroup.children.filter((p) => p.visible && p.userData.device)
      const hit = raycaster.intersectObjects(pins, false)[0]
      return hit ? hit.object.userData.device : null
    }

    const onMove = (ev) => {
      const rect = host.getBoundingClientRect()
      tipPos.current = { x: ev.clientX - rect.left, y: ev.clientY - rect.top }
      const device = pickDevice(ev)
      renderer.domElement.style.cursor = device ? 'pointer' : 'grab'
      if (tipEl.current) {
        tipEl.current.style.left = `${tipPos.current.x}px`
        tipEl.current.style.top = `${tipPos.current.y}px`
      }
      setHover((h) => (h?.id === device?.id ? h : device))
    }
    const onLeave = () => setHover(null)
    const onClick = (ev) => {
      const device = pickDevice(ev)
      if (device) {
        flyTo.current(device.place)
        onSelect(device.id)
      }
    }
    renderer.domElement.addEventListener('pointermove', onMove)
    renderer.domElement.addEventListener('pointerleave', onLeave)
    renderer.domElement.addEventListener('click', onClick)

    const ro = new ResizeObserver(([entry]) => {
      const w = Math.max(1, Math.floor(entry.contentRect.width))
      const h = Math.max(1, Math.floor(entry.contentRect.height))
      camera.aspect = w / h
      camera.updateProjectionMatrix()
      renderer.setSize(w, h)
    })
    ro.observe(host)

    // Recolour when day/night mode flips.
    const mo = new MutationObserver(() => setSkin((s) => s + 1))
    mo.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })

    kit.current = { scene, camera, renderer, controls, globeGroup, pinsGroup, ocean, atmosphere, dots, host, colors }

    return () => {
      cancelAnimationFrame(raf)
      ro.disconnect()
      mo.disconnect()
      renderer.domElement.removeEventListener('pointermove', onMove)
      renderer.domElement.removeEventListener('pointerleave', onLeave)
      renderer.domElement.removeEventListener('click', onClick)
      controls.dispose()
      clearPins(pinsGroup)
      dotGeo.dispose()
      dots.material.dispose()
      dotTex.dispose()
      ocean.geometry.dispose()
      ocean.material.dispose()
      atmosphere.geometry.dispose()
      atmosphere.material.dispose()
      renderer.dispose()
      if (renderer.domElement.parentNode === host) host.removeChild(renderer.domElement)
      kit.current = null
    }
    // Built once; later changes flow through the effects below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // ---- recolour on theme change ----------------------------------------------------------
  useEffect(() => {
    const k = kit.current
    if (!k) return
    const colors = readColors(k.host)
    k.colors = colors
    k.ocean.material.color.copy(colors.ocean)
    k.atmosphere.material.color.copy(colors.atmosphere)
    k.dots.material.color.copy(colors.dot)
  }, [skin])

  // ---- (re)build the pins when the devices, the filter, the selection or the theme change --
  useEffect(() => {
    const k = kit.current
    if (!k) return
    clearPins(k.pinsGroup)
    const colors = k.colors
    const pinTex = { normal: pinTexture(`#${colors.pin.getHexString()}`), live: pinTexture(`#${colors.online.getHexString()}`) }
    const haloTex = haloTexture()

    placed.forEach((d, i) => {
      const live = d.online
      const picked = d.id === selectedId
      const [x, y, z] = latLonToVec3(d.place.lat, d.place.lon, R)
      const dir = new THREE.Vector3(x, y, z).normalize()

      const mat = new THREE.SpriteMaterial({ map: live || picked ? pinTex.live : pinTex.normal, depthTest: true, transparent: true })
      const pin = new THREE.Sprite(mat)
      pin.position.set(x, y, z)
      pin.center.set(0.5, 0) // the tip sits on the surface
      const scale = picked ? 0.13 : 0.092
      pin.scale.set(scale * 0.75, scale, 1)
      pin.userData = { device: d, dir, phase: i * 1.7 }
      k.pinsGroup.add(pin)

      if (live || picked) {
        const halo = new THREE.Sprite(new THREE.SpriteMaterial({
          map: haloTex, color: live ? colors.online : colors.pin,
          transparent: true, depthTest: true, blending: THREE.AdditiveBlending, depthWrite: false,
        }))
        halo.position.set(x * 1.002, y * 1.002, z * 1.002)
        const base = picked ? 0.17 : 0.13
        halo.scale.set(base, base, 1)
        k.pinsGroup.add(halo)
        pin.userData.halo = halo
        pin.userData.haloBase = base
      }
    })
    // textures are owned by these sprites; clearPins disposes them on the next rebuild/unmount
    k.pinsGroup.userData.tex = [pinTex.normal, pinTex.live, haloTex]
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [placedKey, selectedId, skin])

  // ---- fly to a device picked elsewhere (the list, a drawer) ------------------------------
  useEffect(() => {
    const d = selectedId && placed.find((p) => p.id === selectedId)
    if (d) flyTo.current(d.place)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId])

  return (
    <div className="globe">
      <div className="map-filters" role="group" aria-label="Show devices seen in">
        {FILTERS.map((f) => (
          <button
            key={f.id} type="button" className={`pill sm ${filter === f.id ? 'active' : ''}`}
            aria-pressed={filter === f.id} onClick={() => setFilter(f.id)}
          >
            {f.label}
          </button>
        ))}
      </div>

      <div className="globe-stage" ref={mount} role="group"
        aria-label={`Globe: ${placed.length} ${placed.length === 1 ? 'device' : 'devices'} shown. Use the list for details.`}>
        {failed && (
          <div className="map-empty">
            This browser can’t draw the globe. Use “See them as a list” above.
          </div>
        )}
        {!failed && placed.length === 0 && (
          <div className="map-empty">
            {withoutPlace
              ? 'These devices have no location yet — it appears within a minute of their first visit.'
              : filter === 'online' ? 'Nobody is online right now.' : 'No devices in this period.'}
          </div>
        )}
        {hover && (
          <div className="globe-tip glass" ref={tipEl} style={{ left: tipPos.current.x, top: tipPos.current.y }}>
            <strong>{hover.name}</strong>
            <span className="muted">{hover.place.label} · {SOURCE[hover.place.source]?.short}</span>
            <span className="muted">
              {hover.online ? 'Online now' : `Seen ${timeAgo(hover.lastSeen)}`} · {hover.downloads} {hover.downloads === 1 ? 'download' : 'downloads'}
            </span>
          </div>
        )}
      </div>

      <div className="map-legend">
        <span><b className="globe-key live" aria-hidden="true" /> Online now — pulsing pin</span>
        <span><b className="globe-key" aria-hidden="true" /> Seen before — steady pin</span>
        <span className="muted">Drag to turn · scroll to zoom · right-drag to pan · click a pin for the device</span>
        <span className="muted">
          {placed.length} on the globe{withoutPlace ? ` · ${withoutPlace} without a location yet` : ''}
        </span>
      </div>
    </div>
  )
}

/** Remove every pin/halo and free its GPU memory. */
function clearPins(group) {
  for (const child of group.children) {
    child.material?.dispose()
  }
  for (const t of group.userData.tex || []) t.dispose()
  group.userData.tex = []
  group.clear()
}
