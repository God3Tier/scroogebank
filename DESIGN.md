# Design system

<!-- impeccable:design-schema 1 -->

## Direction

The visual world is an operations ledger: calm institutional surfaces, explicit
state language, compact rows, and a persistent evidence workspace. It borrows
from risk-review binders and market terminals only where their structure helps
agents compare time, materiality, evidence, and action. It avoids decorative
finance imagery, generic metric-card grids, and trading-screen theatrics.

## Mode

Operate. Scanability, confidence, and safe completion outrank spectacle.

## Palette

- Ledger ink — `#17212B`: primary text and navigation.
- Work paper — `#F4F5F1`: application background.
- Sheet white — `#FFFFFF`: focused work surfaces.
- Rule — `#D7DCD8`: structure and dividers.
- Signal amber — `#B76518`: attention, stale evidence, and monitoring.
- Decision blue — `#2157A6`: focus and primary action.
- Verified teal — `#176B61`: evidence and completed states.
- Risk red — `#A53A36`: destructive or failed states only.

Color always accompanies text or iconography; it never carries status alone.

## Typography

- Archivo Variable: headings and navigation; compact, direct, and legible in
  dense operational layouts.
- Source Sans 3 Variable: body copy, controls, and long evidence text.
- IBM Plex Mono: identifiers, timestamps, scores, and measurements only.

Headings stay sentence case. Numeric columns use tabular numerals. Body measure
does not exceed 72 characters in the detail workspace.

## Layout

The desktop alert route is a two-pane queue and investigation workspace beneath a
quiet product rail. The queue is narrow enough to compare alerts quickly; the
detail pane owns evidence and decisions. At tablet widths the panes become a
master/detail flow. Mobile stacks the same information without hiding evidence.

## Signature

The signal trace is a thin, continuous timeline running through the alert queue.
Each alert attaches a timestamp and status marker to it, making event order and
unread work immediately legible without turning every alert into a card.

## Components

- Navigation rail with role and environment context.
- Filterable alert queue with cursor-based continuation.
- Detail header with status, score version, and decision-support label.
- Exposure strip and ordered explanation ledger.
- Evidence list with visible source domain and retrieval time.
- Decision bar with explicit action consequences.
- Inline feedback form after review.
- Loading skeleton, actionable error notice, and directed empty state.

## Interaction and motion

One authored transition moves focus from the selected queue row into the detail
workspace. Defaults remain visible before animation, duration stays below 240ms,
and `prefers-reduced-motion` disables movement. Hover never substitutes for
selection, and every interactive element has a visible keyboard focus state.

## Content

Use the contract vocabulary: alert, evidence, exposure, explanation, decision,
and feedback. Never imply that `INFORM_CLIENT` sends a message. AI briefings are
labelled and uncertainty remains visible when supplied.
