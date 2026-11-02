import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it, vi } from "vitest"
import { redoCommand, undoCommand } from "@milkdown/kit/plugin/history"
import { insertImageCommand, toggleLinkCommand, toggleStrongCommand, wrapInBulletListCommand, wrapInHeadingCommand } from "@milkdown/kit/preset/commonmark"
import { insertTableCommand } from "@milkdown/kit/preset/gfm"
import { FormatToolbar } from "@/forms/markdown-toolbar"

describe("the formatting toolbar", () => {
  it("runs the editor's own command for each button", async () => {
    const user = userEvent.setup()
    const run = vi.fn()
    render(<FormatToolbar run={run} images={false} trailing={<span>tabs</span>} />)

    await user.click(screen.getByRole("button", { name: "Bold" }))
    await user.click(screen.getByRole("button", { name: "Heading" }))
    await user.click(screen.getByRole("button", { name: "Bulleted list" }))
    await user.click(screen.getByRole("button", { name: "Table" }))
    await user.click(screen.getByRole("button", { name: "Undo" }))
    await user.click(screen.getByRole("button", { name: "Redo" }))
    expect(run.mock.calls).toEqual([
      [toggleStrongCommand.key],
      [wrapInHeadingCommand.key, 2],
      [wrapInBulletListCommand.key],
      [insertTableCommand.key, { row: 3, col: 3 }],
      [undoCommand.key],
      [redoCommand.key],
    ])
    expect(screen.getByRole("toolbar", { name: "Formatting" })).toHaveTextContent("tabs")
  })

  it("offers a picture only where the field allows pictures, and adds a link or a picture by its address", async () => {
    const user = userEvent.setup()
    const run = vi.fn()
    const { rerender } = render(<FormatToolbar run={run} images={false} />)
    expect(screen.queryByRole("button", { name: "Picture" })).not.toBeInTheDocument()

    await user.click(screen.getByRole("button", { name: "Link" }))
    await user.type(await screen.findByLabelText("Link address"), "https://example.test/a{Enter}")
    expect(run).toHaveBeenLastCalledWith(toggleLinkCommand.key, { href: "https://example.test/a" })

    rerender(<FormatToolbar run={run} images />)
    await user.click(screen.getByRole("button", { name: "Picture" }))
    await user.type(await screen.findByLabelText("Picture address"), "/files/a.png")
    await user.click(screen.getByRole("button", { name: "Add" }))
    expect(run).toHaveBeenLastCalledWith(insertImageCommand.key, { src: "/files/a.png", alt: "" })
  })

  it("adds nothing for an empty address", async () => {
    const user = userEvent.setup()
    const run = vi.fn()
    render(<FormatToolbar run={run} images={false} />)
    await user.click(screen.getByRole("button", { name: "Link" }))
    await user.type(await screen.findByLabelText("Link address"), "   {Enter}")
    expect(run).not.toHaveBeenCalled()
  })
})
