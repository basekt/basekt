import { useEffect, useMemo, useRef, useState } from "react"
import { Crepe, CrepeFeature } from "@milkdown/crepe"
import { insert } from "@milkdown/kit/utils"
import katex from "katex"
import { Sigma } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { formulaCategories, type FormulaPreset } from "@/forms/formula-presets"
import "@milkdown/crepe/theme/common/style.css"
import "katex/dist/katex.min.css"
import "@/forms/markdown-editor.css"

export interface MarkdownEditorProps {
  /** The Markdown the editor starts with. It is not controlled: change `key` to start it over. */
  value: string
  /** Whether formulas are allowed: `$x^2$` inline and `$$ ... $$` as a block. */
  math: boolean
  invalid: boolean
  describedBy?: string
  onChange: (markdown: string) => void
}

/**
 * A visual Markdown editor: headings, lists, tables, links and, where the field allows them, formulas drawn by KaTeX
 * as they are typed, with a menu of formulas to start from. It reads and writes Markdown, so what it saves is the same
 * text the source box shows. It is a separate chunk, loaded only when a form has a Markdown field.
 */
export default function MarkdownEditor({ value, math, invalid, describedBy, onChange }: MarkdownEditorProps) {
  const host = useRef<HTMLDivElement>(null)
  const editor = useRef<Crepe | null>(null)
  const report = useRef(onChange)
  report.current = onChange

  useEffect(() => {
    const root = host.current
    if (!root) return
    const crepe = new Crepe({
      root,
      defaultValue: value,
      features: {
        [CrepeFeature.Latex]: math,
        // The image block would write the picture's ratio over its alt text (`![1.00](...)`), so pictures stay plain
        // Markdown images: they show in the editor, keep their alt text, and are added in the Markdown tab.
        [CrepeFeature.ImageBlock]: false,
        // CodeMirror is the heaviest part of the editor. Only a field with formulas needs it: the formula box is one.
        [CrepeFeature.CodeMirror]: math,
      },
    })
    crepe.on((listener) => listener.markdownUpdated((_ctx, markdown) => report.current(markdown)))

    let created = false
    let cancelled = false
    void crepe.create().then(() => {
      created = true
      editor.current = crepe
      if (cancelled) void crepe.destroy()
    })
    return () => {
      cancelled = true
      editor.current = null
      if (created) void crepe.destroy()
    }
    // The editor keeps its own text after it starts, so it is only built again when the caller changes its key.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** Puts [latex] at the cursor, as `$...$` in the line or as a `$$` block of its own. */
  const insertFormula = (latex: string, block: boolean) => {
    editor.current?.editor.action(insert(block ? `$$\n${latex}\n$$` : `$${latex}$`, !block))
  }

  return (
    <div className="markdown-editor-frame space-y-1.5">
      {math && <FormulaMenu onInsert={insertFormula} />}
      <div ref={host} className="markdown-editor" data-invalid={invalid || undefined} aria-describedby={describedBy} />
    </div>
  )
}

/** A button that opens the formulas by group, each drawn as it will look, to put in the text. */
function FormulaMenu({ onInsert }: { onInsert: (latex: string, block: boolean) => void }) {
  const [open, setOpen] = useState(false)
  const [category, setCategory] = useState(formulaCategories[0]!.id)
  const [block, setBlock] = useState(false)
  const current = formulaCategories.find((c) => c.id === category) ?? formulaCategories[0]!
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button type="button" variant="outline" size="sm">
          <Sigma aria-hidden /> Formula
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-[28rem] max-w-[calc(100vw-2rem)] space-y-3 p-3">
        <div role="tablist" aria-label="Kinds of formula" className="flex flex-wrap gap-1">
          {formulaCategories.map((c) => (
            <button
              key={c.id}
              type="button"
              role="tab"
              aria-selected={c.id === category}
              onClick={() => setCategory(c.id)}
              className={`rounded-md px-2 py-1 text-xs font-medium ${c.id === category ? "bg-muted text-foreground" : "text-muted-foreground hover:text-foreground"}`}
            >
              <span aria-hidden className="mr-1">{c.icon}</span>
              {c.label}
            </button>
          ))}
        </div>
        <div className="grid max-h-64 grid-cols-4 gap-1.5 overflow-y-auto">
          {current.presets.map((preset) => (
            <PresetButton
              key={preset.id}
              preset={preset}
              onPick={() => {
                onInsert(preset.latex, block)
                setOpen(false)
              }}
            />
          ))}
        </div>
        <label className="text-muted-foreground flex items-center gap-2 text-xs">
          <input type="checkbox" checked={block} onChange={(e) => setBlock(e.target.checked)} />
          Put it on a line of its own, centered
        </label>
      </PopoverContent>
    </Popover>
  )
}

function PresetButton({ preset, onPick }: { preset: FormulaPreset; onPick: () => void }) {
  // The presets are constants of this panel, so what KaTeX makes of them is safe to place as HTML.
  const html = useMemo(() => katex.renderToString(preset.latex, { throwOnError: false }), [preset.latex])
  return (
    <button
      type="button"
      title={preset.label}
      aria-label={preset.label}
      onClick={onPick}
      className="hover:bg-accent flex min-h-12 items-center justify-center overflow-hidden rounded-md border px-1 py-1.5 text-sm"
    >
      <span dangerouslySetInnerHTML={{ __html: html }} />
    </button>
  )
}
