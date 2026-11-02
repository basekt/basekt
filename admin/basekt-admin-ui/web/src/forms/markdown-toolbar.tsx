import { useState, type ReactNode } from "react"
import type { CmdKey } from "@milkdown/kit/core"
import { redoCommand, undoCommand } from "@milkdown/kit/plugin/history"
import {
  insertImageCommand,
  toggleEmphasisCommand,
  toggleInlineCodeCommand,
  toggleLinkCommand,
  toggleStrongCommand,
  wrapInBlockquoteCommand,
  wrapInBulletListCommand,
  wrapInHeadingCommand,
  wrapInOrderedListCommand,
} from "@milkdown/kit/preset/commonmark"
import { insertTableCommand, toggleStrikethroughCommand } from "@milkdown/kit/preset/gfm"
import {
  Bold,
  Code,
  Heading2,
  Heading3,
  Image,
  Italic,
  Link,
  List,
  ListOrdered,
  Quote,
  Redo2,
  Strikethrough,
  Table,
  Undo2,
  type LucideIcon,
} from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"

/** Runs one of the editor's commands, with what it takes. */
export type RunCommand = <T>(command: CmdKey<T>, payload?: T) => void

/** One button: what it is called, its icon, and the command it runs. */
interface Tool {
  label: string
  icon: LucideIcon
  run: (run: RunCommand) => void
}

const marks: Tool[] = [
  { label: "Bold", icon: Bold, run: (run) => run(toggleStrongCommand.key) },
  { label: "Italic", icon: Italic, run: (run) => run(toggleEmphasisCommand.key) },
  { label: "Strikethrough", icon: Strikethrough, run: (run) => run(toggleStrikethroughCommand.key) },
  { label: "Code", icon: Code, run: (run) => run(toggleInlineCodeCommand.key) },
]

const blocks: Tool[] = [
  { label: "Heading", icon: Heading2, run: (run) => run(wrapInHeadingCommand.key, 2) },
  { label: "Subheading", icon: Heading3, run: (run) => run(wrapInHeadingCommand.key, 3) },
  { label: "Bulleted list", icon: List, run: (run) => run(wrapInBulletListCommand.key) },
  { label: "Numbered list", icon: ListOrdered, run: (run) => run(wrapInOrderedListCommand.key) },
  { label: "Quote", icon: Quote, run: (run) => run(wrapInBlockquoteCommand.key) },
]

const history: Tool[] = [
  { label: "Undo", icon: Undo2, run: (run) => run(undoCommand.key) },
  { label: "Redo", icon: Redo2, run: (run) => run(redoCommand.key) },
]

/**
 * The formatting buttons above a Markdown editor: marks, blocks, a link, a picture where the field allows pictures, a
 * table, undo and redo, then [formula] and, at the end of the row, [trailing] (the visual and source tabs). Each button
 * runs the editor's own command, so it writes the same Markdown as typing would. A button keeps the editor's selection,
 * so it formats what was selected.
 */
export function FormatToolbar({ run, images, formula, trailing }: {
  run: RunCommand
  images: boolean
  formula?: ReactNode
  trailing?: ReactNode
}) {
  const button = (tool: Tool) => (
    <Button
      key={tool.label}
      type="button"
      variant="ghost"
      size="icon"
      className="size-8"
      aria-label={tool.label}
      title={tool.label}
      // Clicking would move the focus out of the text and lose the selection the command acts on.
      onMouseDown={(event) => event.preventDefault()}
      onClick={() => tool.run(run)}
    >
      <tool.icon />
    </Button>
  )
  return (
    <div role="toolbar" aria-label="Formatting" data-slot="markdown-toolbar" className="flex flex-wrap items-center gap-0.5 rounded-md border bg-muted/40 p-0.5">
      {marks.map(button)}
      <Separator />
      {blocks.map(button)}
      <Separator />
      <AddressButton label="Link" icon={Link} placeholder="https://…" onAdd={(href) => run(toggleLinkCommand.key, { href })} />
      {images && <AddressButton label="Picture" icon={Image} placeholder="https://… or /files/…" onAdd={(src) => run(insertImageCommand.key, { src, alt: "" })} />}
      {button({ label: "Table", icon: Table, run: (r) => r(insertTableCommand.key, { row: 3, col: 3 }) })}
      <Separator />
      {history.map(button)}
      {formula && (
        <>
          <Separator />
          {formula}
        </>
      )}
      {trailing && <div className="ml-auto">{trailing}</div>}
    </div>
  )
}

function Separator() {
  return <span aria-hidden className="bg-border mx-0.5 h-5 w-px" />
}

/** A button that asks for an address (a link's or a picture's) and adds it at the selection. */
function AddressButton({ label, icon: Icon, placeholder, onAdd }: { label: string; icon: LucideIcon; placeholder: string; onAdd: (address: string) => void }) {
  const [open, setOpen] = useState(false)
  const [address, setAddress] = useState("")
  const submit = (event: { preventDefault: () => void; stopPropagation: () => void }) => {
    event.preventDefault()
    event.stopPropagation()
    const trimmed = address.trim()
    if (trimmed) onAdd(trimmed)
    setAddress("")
    setOpen(false)
  }
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button type="button" variant="ghost" size="icon" className="size-8" aria-label={label} title={label} onMouseDown={(event) => event.preventDefault()}>
          <Icon />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-80 p-2">
        {/* Not a <form>: this sits inside the record's form, and a nested form would submit the record. */}
        <div className="flex gap-2" role="group" aria-label={label}>
          <Input
            autoFocus
            value={address}
            onChange={(event) => setAddress(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") submit(event)
            }}
            placeholder={placeholder}
            aria-label={`${label} address`}
          />
          <Button type="button" size="sm" onClick={submit}>
            Add
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  )
}
