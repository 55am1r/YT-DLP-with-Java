import { useState } from 'react'

export default function CopyLinkButton({ url }) {
  const [copied, setCopied] = useState(false)

  async function copy() {
    try {
      await navigator.clipboard.writeText(url)
    } catch {
      const field = document.createElement('textarea')
      field.value = url
      field.setAttribute('readonly', '')
      field.style.position = 'fixed'
      field.style.opacity = '0'
      document.body.appendChild(field)
      field.select()
      document.execCommand('copy')
      field.remove()
    }
    setCopied(true)
    window.setTimeout(() => setCopied(false), 1800)
  }

  return (
    <button
      className={`icon-round sm copy-link ${copied ? 'copied' : ''}`}
      type="button"
      onClick={copy}
      title={copied ? 'Link copied' : 'Copy video link'}
      aria-label={copied ? 'Video link copied' : 'Copy video link'}
    >
      <i className={`fa-solid ${copied ? 'fa-check' : 'fa-link'}`} />
    </button>
  )
}
