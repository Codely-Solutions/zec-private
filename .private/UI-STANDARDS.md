# UI standards

Use this guide when changing screens or shared controls. Material 3 and Android conventions provide the foundation; Jupiter supplies the default reference for swap hierarchy, compact feedback and details on demand. Adapt patterns to native Android and the wallet's existing behavior.

## Choose the component by its purpose

- Reuse existing screens and shared controls. Use Material 3 components, semantic color/type/shape roles and system appearance settings, including readable true-black OLED surfaces.
- Identify the user's intended action and the specific defect before changing a screen. Preserve the surrounding structure and behavior when a local correction meets the goal. Resolve ambiguous feedback against the referenced screen and action before expanding the change.
- Give the main action clear button emphasis. Use lower-emphasis buttons for secondary actions, recognizable icon buttons for compact actions, links for navigation and ordinary text for information. Preserve each element's meaning and emphasis when restyling it.
- Make the visual grouping match the action target. Use a standard outlined button when a separated label and trailing cue need a shared boundary; retain its native padding and state styling. Choose emphasis for the action's role and surrounding hierarchy.
- Keep routine action labels short and naturally single-line. Allow wrapping when translation, narrow space or larger text requires it; preserve the complete action and a comfortable target. Place supporting explanation beside the control. A two-line information label remains ordinary text.
- Give a navigation row one action across its full width, with leading-aligned text and a trailing decorative glyph that shares that action. Keep the glyph separate from the localized label and omit redundant accessibility text. Use a separate control when a second action is necessary to the reader's task. Style standalone secondary links for their local form hierarchy; use consistent list alignment for repeated navigation rows with the same purpose.
- Present data in aligned label/value rows and keep copy affordances with the values they copy. Place external-navigation links in their navigation context. Show identifiers when readers need to inspect, copy or distinguish them; a destination label is sufficient for a single explorer link. Number genuinely multiple transactions within the same leg and preserve their distinct targets.
- Group contextual help with its label, keeping a consistent visual gap and an accessible target. Center the icon beside the complete label when it wraps; align the separate value column independently.

## Make information useful

- State the action, result or decision information directly. Use explicit labels such as **Estimate unavailable** for missing data. Preserve precise asset amounts and distinguish estimates, included allowances and actual payments.
- Choose visible information and controls for the reader's task. Keep implementation inputs, such as an ID used to build a URL, internal unless exposing them serves that task. Keep useful cues adjacent to their targets; use action feedback to confirm completion.
- Show actionable feedback inline. For a field's info icon, put a single short sentence behind the tap that tells the reader what the value means for their action. Use expandable details when they need several facts. Use existing primary-action wording for deliberate continuation where approved, while retaining payment review and authentication.
- Keep routine background-check results in contextual details. Give warnings and status that change the user's next action visible emphasis; place reference sources and supporting figures in the surrounding label/value layout.
- Use semantic error, warning, selected and disabled styling. Pair color with meaningful text or icons. Honor balance/reveal protections in new controls and accessibility semantics.
- Keep explicitly selected assets and entered recipients visible when their networks conflict. Show an actionable inline error and block the quote until corrected. Scope contact selection to the selected network while keeping asset selection editable; distinguish declared network metadata from ambiguous raw address syntax.

## Make the layout accessible and verify it

- Use standard accessible components, at least 48dp touch targets and specific descriptions for icon actions. Keep adjacent targets distinct. Let text and rows wrap or reflow as font size grows before reducing readability.
- Inspect changed controls in actual renders, including affected light/dark/OLED and large-text states. Compare matched before/after state, check hierarchy, alignment, contrast, precision, wrapping and scrolling, and correct genuine defects before handoff. Follow [the capture procedure](UI-REVIEW.md); synthetic screenshots establish layout, while wallet acceptance establishes execution.
- Keep justified exceptions local to the affected component and explain material tradeoffs in its review. Update this guide when a recurring product choice changes.
- Distinguish platform requirements, supported patterns and product judgment when recommending a design. Verify claims about standards against primary guidance and explain meaningful tradeoffs before changing a recommendation. Compare user purpose and interaction requirements when reviewing similar-looking controls; preserve already-appropriate designs.

References: [Material buttons](https://m3.material.io/components/buttons/guidelines), [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults), [Material tooltips](https://m3.material.io/components/tooltips/guidelines).
