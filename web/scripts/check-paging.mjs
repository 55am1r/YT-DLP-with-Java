// Which page buttons does the admin panel's pager show? Run:
//   node scripts/check-paging.mjs
import { PAGE_SIZES, pageList } from '../src/admin/paging.js'

let failures = 0
const expect = (want, page, pages, why) => {
  const got = pageList(page, pages)
  if (JSON.stringify(got) !== JSON.stringify(want)) {
    failures++
    console.error(`FAIL ${why}: pageList(${page}, ${pages}) = ${JSON.stringify(got)}, wanted ${JSON.stringify(want)}`)
  }
}

expect([1], 1, 1, 'a single page')
expect([1, 2, 3, 4, 5, 6, 7], 4, 7, 'up to 7 pages → every one, no gaps')
expect([1, 2, '…', 24], 1, 24, 'first page → its neighbour, then the end')
expect([1, '…', 23, 24], 24, 24, 'last page → the start, then its neighbour')
expect([1, '…', 5, 6, 7, '…', 24], 6, 24, 'middle → both ends and a neighbour either side')
expect([1, 2, 3, 4, 5, '…', 24], 4, 24, 'a gap that would hide one page shows that page instead')
expect([1, '…', 20, 21, 22, 23, 24], 21, 24, 'same near the end')
expect([1, 2, 3, '…', 8], 2, 8, '8 pages, near the start')

for (let pages = 1; pages <= 60; pages++) {
  for (let page = 1; page <= pages; page++) {
    const list = pageList(page, pages)
    const numbers = list.filter((p) => p !== '…')
    const sorted = numbers.every((p, i) => i === 0 || p > numbers[i - 1])
    const gapsHideTwo = list.every((p, i) => p !== '…' || list[i + 1] - list[i - 1] > 2)
    if (!numbers.includes(1) || !numbers.includes(pages) || !numbers.includes(page) || !sorted || !gapsHideTwo || list.length > 7) {
      failures++
      console.error(`FAIL pageList(${page}, ${pages}) = ${JSON.stringify(list)}`)
    }
  }
}

if (JSON.stringify(PAGE_SIZES) !== JSON.stringify([10, 20, 30, 50, 100])) {
  failures++
  console.error(`FAIL page sizes are ${PAGE_SIZES}`)
}

if (failures) process.exit(1)
console.log('paging OK — page buttons for 1–60 pages, sizes 10/20/30/50/100')
