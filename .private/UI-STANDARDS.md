# UI standards

Use this guide when changing screens or shared controls. Material 3 and Android conventions provide the foundation; Jupiter supplies the default reference for swap hierarchy, compact feedback and details on demand. Adapt patterns to native Android and the wallet's existing behavior.

## Choose the component by its purpose

- Reuse existing screens and shared controls. Use Material 3 components, semantic color/type/shape roles and system appearance settings, including readable true-black OLED surfaces.
- Give the main action clear button emphasis. Use lower-emphasis buttons for secondary actions, recognizable icon buttons for compact actions, links for navigation and ordinary text for information. Preserve each element's meaning and emphasis when restyling it.
- Keep routine action labels short and naturally single-line. Allow wrapping when translation, narrow space or larger text requires it; preserve the complete action and a comfortable target. Place supporting explanation beside the control. A two-line information label remains ordinary text.
- Present details as aligned label/value rows. For transaction IDs, make tapping the row copy the full value, retain a small decorative copy cue, and give the explorer a distinct trailing button. Display each ID once in its relevant leg and provide action labels and copy feedback.

## Make information useful

- State the action, result or decision information directly. Use explicit labels such as **Estimate unavailable** for missing data. Preserve precise asset amounts and distinguish estimates, included allowances and actual payments.
- Keep ordinary feedback inline. Put supplementary explanation in a short tooltip or expandable detail. Use existing primary-action wording for deliberate continuation where approved, while retaining payment review and authentication.
- Use semantic error, warning, selected and disabled styling. Pair color with meaningful text or icons. Honor balance/reveal protections in new controls and accessibility semantics.

## Make the layout accessible and verify it

- Use standard accessible components, at least 48dp touch targets and specific descriptions for icon actions. Keep adjacent targets distinct. Let text and rows wrap or reflow as font size grows before reducing readability.
- Inspect changed controls in actual renders, including affected light/dark/OLED and large-text states. Compare matched before/after state, check hierarchy, alignment, contrast, precision, wrapping and scrolling, and correct genuine defects before handoff. Follow [the capture procedure](UI-REVIEW.md); synthetic screenshots establish layout, while wallet acceptance establishes execution.
- Keep justified exceptions local to the affected component and explain material tradeoffs in its review. Update this guide when a recurring product choice changes.

References: [Material buttons](https://m3.material.io/components/buttons/guidelines), [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults), [Material tooltips](https://m3.material.io/components/tooltips/guidelines).
