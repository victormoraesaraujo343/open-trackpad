<!-- Fonte de verdade do visual. As telas ficam nos artifacts ligados abaixo; -->
<!-- este arquivo guarda o sistema de design e os links, para não depender do chat. -->

---
name: OpenTrackpad
colors:
  surface: '#121314'
  surface-dim: '#121314'
  surface-bright: '#38393a'
  surface-container-lowest: '#0d0e0f'
  surface-container-low: '#1b1c1d'
  surface-container: '#1f2021'
  surface-container-high: '#292a2b'
  surface-container-highest: '#343536'
  on-surface: '#e3e2e3'
  on-surface-variant: '#c2cab0'
  inverse-surface: '#e3e2e3'
  inverse-on-surface: '#303031'
  outline: '#8c947c'
  outline-variant: '#424936'
  surface-tint: '#98da27'
  primary: '#ccff80'
  on-primary: '#213600'
  primary-container: '#a3e635'
  on-primary-container: '#416400'
  inverse-primary: '#446900'
  secondary: '#c0c7d1'
  on-secondary: '#2a3139'
  secondary-container: '#434a52'
  on-secondary-container: '#b2b9c3'
  tertiary: '#ffecd8'
  on-tertiary: '#462b00'
  tertiary-container: '#ffc984'
  on-tertiary-container: '#7e5100'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#b2f746'
  primary-fixed-dim: '#98da27'
  on-primary-fixed: '#121f00'
  on-primary-fixed-variant: '#334f00'
  secondary-fixed: '#dce3ed'
  secondary-fixed-dim: '#c0c7d1'
  on-secondary-fixed: '#151c23'
  on-secondary-fixed-variant: '#40474f'
  tertiary-fixed: '#ffddb5'
  tertiary-fixed-dim: '#ffb957'
  on-tertiary-fixed: '#2a1800'
  on-tertiary-fixed-variant: '#643f00'
  background: '#121314'
  on-background: '#e3e2e3'
  surface-variant: '#343536'
typography:
  headline-sm:
    fontFamily: Space Grotesk
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  label-sm:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 14px
    letterSpacing: 0.05em
  label-xs:
    fontFamily: Inter
    fontSize: 9px
    fontWeight: '700'
    lineHeight: 12px
    letterSpacing: 0.1em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 2px
  rail-width: 48px
  gutter: 8px
  touch-target: 44px
---

# OpenTrackpad — Design System (MINIMAL)

OpenTrackpad turns an Android smartphone into a dedicated Linux companion peripheral over USB. Its primary function is a native multi-touch trackpad, later expanding into shortcut controls, a radial quick-action menu, screen mirroring, an extended display, and hybrid display/control modes.

## PRIMARY DEVICE — non-negotiable
- modern 6.5–6.8 inch candybar smartphone
- phone is rotated horizontally: LANDSCAPE ONLY for active-use screens
- must feel like a smartphone UI in landscape, NOT a tablet dashboard
- edge-to-edge layout; respect camera cutouts and Android gesture-safe areas
- never use portrait for active trackpad/display states
- avoid tablet-style large cards, side navigation drawers, large headings, or dashboard spacing

## CORE UX
- the central trackpad area is the main interaction surface and the visual priority whenever present
- narrow vertical shortcut rails may occupy the left and right edges
- compact top status area; no tall app bars, no wasted vertical space
- large comfortable touch targets, very little wasted space
- the phone should visually feel like a dedicated hardware peripheral when placed flat on a desk, not a generic mobile app

## THEME — MINIMAL
- deep graphite / near-black background (#0E0F10 base, #121314 surfaces)
- subtle charcoal panels (#1B1D1F), thin subtle borders (#2A2D30)
- soft rounded rectangles (8px standard radius), low visual noise
- white and soft-gray typography
- **Lime Green (#A3E635)** used SPARINGLY — only for connected, active, or selected states
- **Slate Grey (#7C838C)** used for secondary UI elements, rails, and inactive states
- **Amber (#F5A524)** used for warnings, errors, or tertiary "Quick Ring" actions
- refined, modern, premium utility-device aesthetic
- no excessive glow

## COMPONENTS TO DEFINE
compact OpenTrackpad wordmark/status; USB connection status pill; profile selector; central trackpad surface; left shortcut rail; right shortcut rail; shortcut button; Quick Ring trigger button; radial Quick Ring menu; settings button; Android keyboard button; source/display selector; compact warning/error card; theme selector; profile editor slots.

## TYPOGRAPHY
- **Headline:** Space Grotesk (geometric, technical)
- **Body/Labels:** Inter (clean, highly readable)
- Strong readability with compact labels; avoid oversized headings in active-use screens

## MOTION
- fast, subtle micro-interactions; tactile pressed states; haptics implied
- no slow transitions that would make a peripheral feel laggy

## CONSISTENCY RULE
Every screen is another state of the same product, not a redesign. Do not change the landscape candybar form factor, top bar height, trackpad geometry, shortcut rail widths, touch target sizing, icon style, typography system, or information architecture between screens.
## Telas e decisões visuais

As telas são desenhadas e revisadas nestes artifacts, não no Google Stitch:

- **Telas do aplicativo** — https://claude.ai/code/artifact/4a4f4ae7-a180-4785-a2e2-cce0eb063677
- **Lista de controles** (o que entra na trilha e em que formato) — https://claude.ai/code/artifact/f8aa869e-b928-4b84-9b7e-34873eefa9d8

A janela de gravação de atalho que aparece **no computador** também é desenhada
ali, na mesma linguagem do aplicativo. Uma superfície nova não nasce fora do padrão.
