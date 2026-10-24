# Zandy Touch Visual Guide

Inputs read: business-context.md, brand-brief.md, design-spec.md (revised by ux-designer), engineering-standards.md, and the real logo (the owner's replacement JPEG, 812x1080) and sample photos in `business/images/`. `design-tokens.css` is the single source of truth for every value below. If this guide and the tokens disagree, the tokens win and this guide has a bug.

Scope: look and feel only. No page HTML, page CSS, JavaScript or layout is defined here (that belongs to ux-designer and the frontend teammate).

Engineering constraints that touch visuals: accessibility to WCAG 2.1 AA is a stated goal (checked below with a script); nothing in engineering-standards.md marked non-negotiable affects the visual layer. The hosting budget (15 USD a month) and the data-cost point below are why everything here is self-hosted and small.

## 1. Audience to decisions

| Audience fact (source) | Decision | Reason |
|---|---|---|
| Local customers, early 20s to late 30s, urban professionals who value style and will pay for quality (business-context) | Black and gold from the real logo, ivory content surface, one serif for headings, lots of empty space, no loud promo styling | Luxury here comes from restraint. The brand brief says the voice is calmer than the loud posters. |
| Most browse on phones, in Kampala, often outdoors (brand-brief, assumption; hosting budget) | 17px body text, 48px tap targets and controls, text contrast of 15:1 on the main surface, light content areas with black only for header, footer and hero | Phone screens in daylight wash out low-contrast text. Dark pages with small grey text are the first thing to fail. |
| Mobile data is expensive and hosting is capped at 15 USD (business-context) | 3 self-hosted font files (71 KB total, latin subset), system-font fallbacks, flat colour instead of gradients, SVG logo and icons, strict image sizes | Every kilobyte is paid for by the customer. Flat gold also avoids the glow and gradient of the JPEG logo, which cannot be reproduced cheaply. |
| Trust is built by past experience, referrals and word of mouth; customers "know the products will satisfy their needs" (business-context) | Trust signals are about delivery and payment ("Pay on delivery", "Delivery fee agreed with you") and a clear own-brand badge, not star ratings or award seals | We have no reviews to show and must not invent any. Honest trust cues match how these customers already buy. |
| Cards are uncommon. Payment is cash or mobile money on delivery. The delivery fee is negotiated per order (business-context, design-spec) | No "Secure checkout", "Free delivery", "Pay on delivery" or "Sale" badges. Pay-on-delivery trust is carried by icon plus a plain sentence (spec reassurance lines). Icons for cash, mobile money, delivery and WhatsApp are in the set. Prices always read "UGX 85,000" | A "secure checkout" lock suggests card payment that does not exist. "Free delivery" would be false. Sale is not in v1 (spec Q9). |
| They find products on social media and WhatsApp, and some want to be told about new arrivals (business-context) | A "New" badge, a WhatsApp icon in the set, a 4:5 portrait photo shape that matches Instagram and WhatsApp status | Photos shot for this site can be reused on social posts without recropping. |
| The brand has an own range ("natural, organic, authentic") beside third-party brands (business-context) | A gold "Zandy Touch" badge reserved for own-brand products only | Makes the own range easy to spot without extra words. |
| Owner likes ASOS, Etsy, Glovo, Walmart: sleek grid, intuitive, door-to-door, efficient (business-context) | Dense-but-calm two-column phone grid, plain white photo backdrops, one primary action colour, no decoration around the buy button | Efficiency over ornament. |
| Staff upload photos in bulk, probably from a phone; until then the backend seeds sample products with public-domain or CC0 photos (business-context) | A photo shot spec a non-photographer can follow, plus a seed-image selection and crop rule (section 7) | Consistent photos make the shop look expensive. Inconsistent ones make it look like a marketplace. |
| Stock is counted, staff adjust it and read a movement history; customers see only Available or Sold out (design-spec) | A Low stock badge and stock sign colours exist for admin only; the customer side shows Sold out, never a count. Plus and minus are always printed with a sign, never colour alone | Showing counts to customers invites haggling and screenshots; staff need to see trouble at a glance in a table on a phone or laptop. |
| Policy documents are placeholders until reviewed (design-spec, business-context) | A Draft notice badge ("Draft text, not yet reviewed") in warning colours, shown on policy pages and the checkout terms panel until approved | Unreviewed legal text must never look final. Warning colour plus words and a left bar is visible without alarming customers. |
| The sample posters carry text, prices and claims baked into the image (samples) | The site never uses images with baked-in text. Words live in HTML. | Baked-in text cannot be translated, searched or read by a screen reader, goes blurry on small screens, and breaks the calm voice. |

## 2. What works for this audience (research)

I tried to study five sites. Honest record of what I could actually see:

- **Fenty Beauty (fetched).** Neutral cream and white base, gold and rose-gold used only as small accents, product photography with a lot of white space, a 2 to 4 column grid, "NEW" and "Bestseller" badges, clean cards (image, name, price). Taken: restraint with metallic colour, product-first photography, short uppercase badges. Not taken: star ratings and strikethrough prices (we have neither), and multi-angle image carousels on the grid (data cost).
- **Jumia Uganda (search results only; the page itself returned 403).** Search coverage says it wins on cash on delivery and logistics but "presents as a generic discount marketplace". Taken: say "pay on delivery" plainly and early. Avoided: discount-led density and red promotional colour, which would undercut black and gold.
- **ASOS, Sephora (fetch failed: timeout and 403).** I could not inspect them, so nothing here is claimed from direct observation. Owner's reasons for ASOS (sleek, clear product information) are used as written in business-context.md. Treat the "plain grid, white photo backdrop" rule as general retail practice and confirm it against the live sites when possible.

What this changed: gold stays small (rules, labels, one button type, icons), photographs sit on plain light backdrops in one aspect ratio, badges are few and short, and trust copy is about delivery and payment.

## 3. Palette

Logo colours were first sampled from `business/images/logo/zandy-logo.jpeg` with ImageMagick (histogram clusters). **Re-sampled after the owner replaced the logo** with `scripts/design/sample_logo.py` (Pillow, throwaway venv). The new file is the same artwork re-cropped from 1080x1080 to 812x1080. Colours did not move materially: wordmark median `#E3A43C` (brightest 10 percent `#FBEBAC`), monogram median `#F2C260` (brightest `#FDF6C5`), field `#000000`. The logo gold is a gradient from amber (`#C07810`) to pale (`#F8D868`); the flat token `#D6A94F` sits inside it and is the shade that gives 9.04:1 with black. **No token was changed.** Original findings:

| Logo element | Sampled values |
|---|---|
| Page field | pure black `#000000` |
| Wordmark gold, main clusters | `#CF9734`, `#DFAD51`, `#D5AD53`, `#D7A84D` |
| Monogram highlight | `#F4CC6A` (and a pale `#F2E2A5` glare) |
| Gold shadow tone | `#9A7435`, `#5B4522` |
| Slogan text | neutral light grey `#E0E0E0` (not warm ivory) |

Reconciliation with brand-brief.md (which estimated by eye):

| Brief value | Final | Change and why |
|---|---|---|
| Gold `#C9A24B` | **Gold `#D6A94F`** | Sampled average of the wordmark clusters. It is slightly lighter and warmer than the estimate and matches the logo on screen. 9.0:1 on black. |
| (none) | **Gold light `#F4CC6A`** | The logo's highlight, used only for hover and focus on black. |
| (none) | **Gold deep `#8A6310`** | Adjusted shade, a darkened form of the logo's shadow tone `#9A7435`, which only reached about 3.9:1 on ivory. `#8A6310` reaches 4.78:1, so gold-toned text is allowed on light surfaces. |
| Black `#0B0B0B` | `#0B0B0B` kept | The logo is `#000`, but near-black is softer on OLED phones and matches the brief. |
| Ivory `#F6F0E4` | `#F6F0E4` kept | Warm surface and text on black. The logo's slogan is neutral grey, so the SVG slogan uses ivory for warmth; this is a deliberate small difference. |

Roles (all values in `design-tokens.css`):

| Role | Token | Hex | Used for |
|---|---|---|---|
| Primary | `--color-primary` | `#0B0B0B` | Header, footer, hero, primary button on light surfaces, headings |
| Accent | `--color-accent` | `#D6A94F` | Gold rules, icons on black, primary button fill on black, own-brand badge |
| Accent text on light | `--color-accent-text` | `#8A6310` | Gold-toned text and borders on ivory or white (never `#D6A94F` there: 1.9:1) |
| Surface | `--color-surface` | `#F6F0E4` | Page background of content areas |
| Surface raised | `--color-surface-raised` | `#FBF8F1` | Cards, panels |
| Field | `--color-surface-field` | `#FFFFFF` | Inputs and the photo backdrop |
| Text | `--color-text` | `#1A1713` | Body and headings on light surfaces |
| Text muted | `--color-text-muted` | `#5C5446` | Secondary text on light surfaces |
| Text inverse | `--color-text-inverse` | `#F6F0E4` | Text on black |
| Text inverse muted | `--color-text-inverse-muted` | `#B8AE9C` | Secondary text on black |
| Success | `--color-success` | `#1E6B3B` (bg `#E3F1E7`) | Order delivered, confirmations |
| Warning | `--color-warning` | `#7A4F00` (bg `#FBEBC8`) | Price changed, low attention notices |
| Error | `--color-error` | `#B42318` (bg `#FBE4E1`) | Field errors, failures |

Dark-surface variants exist for success, warning and error (`--color-on-dark-*`). Border colours: `--color-border` (`#D9CFBC`) is decorative only; `--color-border-strong` (`#8C826F`) is the one used for inputs and any border that is the only sign of a control.

### Contrast ratios (from `scripts/design/contrast.py`, WCAG 2.1 formula, all 56 pairs pass, re-run after the logo update)

Text needs 4.5:1, large text and UI parts 3:1.

| Foreground | Background | Ratio | Needs | Use |
|---|---|---|---|---|
| `#1A1713` | `#F6F0E4` | 15.74:1 | 4.5 | body text on page surface (pass) |
| `#1A1713` | `#FBF8F1` | 16.84:1 | 4.5 | body text on card (pass) |
| `#1A1713` | `#FFFFFF` | 17.86:1 | 4.5 | body text on white (inputs) (pass) |
| `#5C5446` | `#F6F0E4` | 6.58:1 | 4.5 | secondary text on surface (pass) |
| `#5C5446` | `#FBF8F1` | 7.04:1 | 4.5 | secondary text on card (pass) |
| `#F6F0E4` | `#0B0B0B` | 17.34:1 | 4.5 | body text on black header/footer (pass) |
| `#B8AE9C` | `#0B0B0B` | 8.97:1 | 4.5 | secondary text on black (pass) |
| `#B8AE9C` | `#1A1814` | 8.08:1 | 4.5 | secondary text on raised dark (pass) |
| `#D6A94F` | `#0B0B0B` | 9.04:1 | 4.5 | gold text/links on black (pass) |
| `#D6A94F` | `#1A1814` | 8.14:1 | 4.5 | gold text on raised dark (pass) |
| `#F4CC6A` | `#0B0B0B` | 12.83:1 | 4.5 | hover gold on black (pass) |
| `#0B0B0B` | `#D6A94F` | 9.04:1 | 4.5 | button label: black on gold (pass) |
| `#0B0B0B` | `#F4CC6A` | 12.83:1 | 4.5 | button hover: black on light gold (pass) |
| `#D6A94F` | `#0B0B0B` | 9.04:1 | 3.0 | gold icon / rule / focus ring on black (pass) |
| `#8A6310` | `#F6F0E4` | 4.78:1 | 4.5 | gold-toned link/text on surface (pass) |
| `#8A6310` | `#FFFFFF` | 5.42:1 | 4.5 | gold-toned text on white (pass) |
| `#D6A94F` | `#0B0B0B` | 9.04:1 | 4.5 | primary button label: gold on black (light-surface button) (pass) |
| `#8C826F` | `#F6F0E4` | 3.34:1 | 3.0 | input border / UI boundary on surface (pass) |
| `#8C826F` | `#FFFFFF` | 3.79:1 | 3.0 | input border on white (pass) |
| `#0B0B0B` | `#F6F0E4` | 17.34:1 | 3.0 | focus ring on light surface (pass) |
| `#1E6B3B` | `#F6F0E4` | 5.74:1 | 4.5 | success text on surface (pass) |
| `#1E6B3B` | `#E3F1E7` | 5.59:1 | 4.5 | success text on its tint (pass) |
| `#7A4F00` | `#F6F0E4` | 6.28:1 | 4.5 | warning text on surface (pass) |
| `#7A4F00` | `#FBEBC8` | 6.05:1 | 4.5 | warning text on its tint (pass) |
| `#B42318` | `#F6F0E4` | 5.79:1 | 4.5 | error text on surface (pass) |
| `#B42318` | `#FFFFFF` | 6.57:1 | 4.5 | error text on white field (pass) |
| `#B42318` | `#FBE4E1` | 5.41:1 | 4.5 | error text on its tint (pass) |
| `#6FCF8E` | `#0B0B0B` | 10.30:1 | 4.5 | success text on black (pass) |
| `#FF8A80` | `#0B0B0B` | 8.62:1 | 4.5 | error text on black (pass) |
| `#F4CC6A` | `#0B0B0B` | 12.83:1 | 4.5 | warning text on black (pass) |
| `#0B0B0B` | `#F6F0E4` | 17.34:1 | 4.5 | Sold out / New badge text if black on ivory (pass) |
| `#F6F0E4` | `#0B0B0B` | 17.34:1 | 4.5 | badge: ivory on black (pass) |
| `#FFFFFF` | `#B42318` | 6.57:1 | 4.5 | white on error (reserved Sale badge) (pass) |
| `#F4CC6A` | `#26221B` | 10.32:1 | 4.5 | primary button hover label: light gold on dark hover (pass) |
| `#D6A94F` | `#26221B` | 7.27:1 | 4.5 | gold label on hovered dark button (pass) |
| `#1A1713` | `#ECE5D4` | 14.22:1 | 4.5 | info text on its tint / secondary hover (pass) |
| `#0B0B0B` | `#C29A43` | 7.50:1 | 4.5 | primary-on-dark pressed state (pass) |
| `#5F574A` | `#E4DCCB` | 5.22:1 | 4.5 | disabled label (exempt, kept readable) (pass) |
| `#5F574A` | `#ECE5D4` | 5.67:1 | 4.5 | disabled field text (exempt, kept readable) (pass) |
| `#8C826F` | `#FBF8F1` | 3.58:1 | 3.0 | control border on card (pass) |
| `#F4CC6A` | `#0B0B0B` | 12.83:1 | 3.0 | focus ring on black (pass) |
| `#0B0B0B` | `#FBF8F1` | 18.56:1 | 3.0 | focus ring on card (pass) |
| `#5C5446` | `#ECE5D4` | 5.95:1 | 4.5 | muted text on tint (Archived pill) (pass) |
| `#1A1713` | `#ECE5D4` | 14.22:1 | 4.5 | Draft / Placed pill text on tint (pass) |
| `#1E6B3B` | `#FFFFFF` | 6.52:1 | 4.5 | stock increase (+N) on white table row (pass) |
| `#1E6B3B` | `#FBF8F1` | 6.15:1 | 4.5 | stock increase on card (pass) |
| `#B42318` | `#FBF8F1` | 6.20:1 | 4.5 | stock decrease (-N) on card (pass) |
| `#7A4F00` | `#FFFFFF` | 7.13:1 | 4.5 | low-stock count on white table row (pass) |
| `#7A4F00` | `#FBF8F1` | 6.72:1 | 4.5 | low-stock count / draft notice text on card (pass) |
| `#7A4F00` | `#FBEBC8` | 6.05:1 | 3.0 | Low stock / draft notice border on its tint (UI boundary) (pass) |
| `#1E6B3B` | `#E3F1E7` | 5.59:1 | 3.0 | Published / Delivered pill border on tint (pass) |
| `#B42318` | `#FBE4E1` | 5.41:1 | 3.0 | Cancelled pill border on tint (pass) |
| `#1A1713` | `#FBF8F1` | 16.84:1 | 3.0 | Draft pill border on card (pass) |
| `#D6A94F` | `#0B0B0B` | 9.04:1 | 4.5 | New badge: gold text on black (pass) |
| `#8A6310` | `#FFFFFF` | 5.42:1 | 4.5 | Featured badge text on white (pass) |
| `#1A1713` | `#F6F0E4` | 15.74:1 | 4.5 | Sold out badge text on ivory (pass) |

Not allowed (fails, listed so nobody tries): gold `#D6A94F` on ivory 1.9:1, on white 2.2:1. Use black text on ivory, gold deep for gold-toned text, and gold only as a rule or a fill behind black text.

## 4. Typography

| Role | Font | Weight | Licence |
|---|---|---|---|
| Headings, wordmark, prices on the product page | Playfair Display, latin subset | 600 | SIL OFL 1.1 (`assets/fonts/LICENSE-PlayfairDisplay.txt`) |
| Body, UI, buttons, badges, prices in lists | Inter, latin subset | 400 and 600 | SIL OFL 1.1 (`assets/fonts/LICENSE-Inter.txt`) |

Self-hosted woff2 in `assets/fonts/`, `font-display: swap`, 3 files, 71 KB total (Playfair 600: 23 KB, Inter 400: 24 KB, Inter 600: 24 KB; the 700 weight was dropped to save data). Source: Fontsource packages (version 5.3.0) of the Google Fonts releases. Playfair matches the serif wordmark of the logo. Inter is very legible on small, low-quality screens. English and Luganda names use the latin subset; if Arabic or other scripts are ever needed, system fonts take over via the fallback stack. The tagline and small labels use Inter 600 uppercase with `--tracking-caps` (echoes the spaced slogan on the logo), not a third font.

Fallback stacks are in the tokens (`--font-heading`: Iowan Old Style, Palatino, Georgia, serif; `--font-body`: system-ui, Segoe UI, Roboto, Arial). Because Playfair is only loaded at 600, never ask for 700 or italic (browsers would fake them).

Scale (mobile to desktop; headings are fluid):

| Token | Size | Use |
|---|---|---|
| `--text-xs` | 13px | Legal, fine print. Never for prices or actions. |
| `--text-sm` | 15px | Metadata, helper text, product brand line |
| `--text-base` | 17px | Body, inputs, buttons (also stops iOS zooming into fields) |
| `--text-lg` | 20px | Lead paragraph, product name on detail page |
| `--text-xl` | 22 to 26px | h3, price on the product page |
| `--text-2xl` | 26 to 34px | h2 |
| `--text-3xl` | 32 to 48px | h1, hero |

Line height: body 1.6, headings 1.2, `--measure` 66ch for reading text. Headings sentence case, Playfair 600. Body weight 400, emphasis 600 (never bold 700).

## 5. Spacing, radius, shadow

- Spacing: 4px base (`--space-1` to `--space-9`: 4, 8, 12, 16, 24, 32, 48, 64, 96). Page side padding 16px on phones, 32px on wide screens. Two-column product grid gap 12px on phones, 24px wider. Content width up to 1152px. Reason: tight on phones so two products fit, generous on desktop.
- Controls: 48px high (`--control-height`); tap targets at least 48px even if the icon is 24px. Admin tables may use 40px.
- Radius: 4px badges and chips, 8px buttons and inputs, 12px cards, panels, dialogs and photos, pill for the quantity stepper and filter chips. Restrained and slightly soft, not bubbly.
- Shadow: only three, warm and low (`--shadow-1` at rest on cards, `--shadow-2` on hover and sticky bars, `--shadow-3` on dialogs and menus). Black surfaces use no shadow; separation there is a gold hairline (1px `--color-rule-gold`). Gold hairlines are decorative only.
- Motion: 120 to 200ms, fades and colour changes only, reduced to 0 for people who ask for less motion.

## 6. Logo, icons and badges

### Logo
Recreated as simple geometric SVG from the real artwork (`business/images/logo/zandy-logo.jpeg`): a wide serif Z monogram, a ribbon wave sweeping in from the left through its diagonal and a small star in the upper curl (redrawn in this revision to follow the new JPEG more closely: the earlier Z was too narrow and heavy and the star sat outside the curl), the serif wordmark "Zandy Touch" and the slogan "BEAUTY WITH CONFIDENCE" between thin rules. The phone number and the three small Skincare, Body Shaping and Perfumes roundels are deliberately left out (the site has its own six category icons; there is no Bedroom category). Wordmark and slogan text are converted to outlines from the self-hosted fonts, so they render the same everywhere.

**Update 25 October 2026 (owner decision):** the site now uses the owner's real logo artwork on dark surfaces, cut from `business/images/logo/zandy-logo.jpeg` as transparent WebP: `assets/logo/zandy-touch-horizontal.webp` (header, admin bar), `assets/logo/zandy-touch-lockup.webp` (footer) and `assets/share/og-default.jpg` (1200x630 share image). The SVG files below remain only for the favicon, app icon and light backgrounds. The SVG mark is a recreation, not the master. **The owner should supply a vector master (SVG, AI or PDF, or the original design file) to replace it.** The wave and star here are approximations of the original curves, and the real artwork's glossy gradient and glow are deliberately not reproduced.

| File (`assets/logo/`) | Use |
|---|---|
| `logo-horizontal.svg` and `-light` | Site header (mark plus wordmark, no slogan) |
| `logo-lockup.svg` and `-light` | Hero, footer, about page (mark, wordmark, slogan) |
| `logo-mark.svg` and `-light` | Small spaces, avatar, loading state |
| `logo-wordmark.svg` and `-light` | Wordmark alone |
| `app-icon.svg` | Source of the PNG app icons (black full-bleed with safe padding) |

Default files are gold on transparent for black backgrounds; `-light` files are black with deep gold for ivory or white backgrounds. Minimum size: horizontal 120px wide, mark 24px. Keep clear space around the logo equal to the height of the Z's top bar times 2. Do not recolour, stretch, add glow or place on photos.

Favicon and app icons (`assets/`): `favicon.svg`, `favicon-16.png`, `favicon-32.png`, `apple-touch-icon-180.png`, `icon-192.png`, `icon-512.png` (also the maskable icon), `site.webmanifest` (theme and background `#0B0B0B`). Rendered with Inkscape by `scripts/design/render_pngs.sh`. At 16px the star and wave are barely visible; that is expected.

### Icons
One family: **Tabler Icons, outline style**, version 3.49.0 (still the latest release on 2026-10-24), MIT licence (`assets/icons/LICENSE-Tabler.txt`), actively maintained. 24px grid, 2px round strokes, `currentColor` so they take the text colour. `assets/icons/` holds **exactly the 43 icons design-spec.md section 6 lists**, renamed to site names (original Tabler name where it differs):

cart (shopping-cart), search, menu (menu-2), close (x), account (user; admin only), phone, whatsapp (brand-whatsapp), email (mail), location (map-pin), delivery (truck), pay-on-delivery (cash), mobile-money (device-mobile-dollar), trust (shield-check; renamed from `secure` to match the spec word and its use for "we confirm every order", never card payment), returns (arrow-back-up), check, warning (alert-triangle), info (info-circle), chevron-up/down/left/right, plus, minus, trash, edit, upload, image (photo), copy, filter, sort (arrows-sort), external-link, **stock (box; new)**, **history (history; new)**, instagram, facebook, tiktok, star, and the six category icons: category-skincare (droplet), category-perfumes (bottle), category-body-shaping (ruler-measure), category-body-care (bath), category-weight-loss (scale), category-health-wellness (heart-plus). There is no bed or Bedroom icon.

Rules: 24px by default, 20px inline with small text, 32px for category tiles and step icons. Gold (`--color-accent`) on black, `--color-text` on light, `--color-accent-text` for a gold accent on light. Never filled and outlined icons together. The star is for "featured" only. `stock` marks the admin stock panel and inventory; `history` marks stock and order history lists. The three social icons are shipped but only used for accounts the owner confirms (spec Q12). The six category icons are a first guess (Tabler has no precise "body shaping" glyph) and should be reviewed with the owner.

### Badges (`assets/badges/`)
Exactly the badges and status pills design-spec.md section 6 lists (14 files). Customer-facing badges: 28px tall, 4px radius, 12px side padding, Inter 600 12px uppercase tracked 0.1em, one line, no icon. Text is outlined so the SVG works as an image; the live site should use the same tokens as HTML text so they can be translated and read aloud.

| Badge | File | Look | Where |
|---|---|---|---|
| New | `badge-new.svg` | Black fill, gold text and 1px gold border | Product card and gallery corner, for 30 days |
| Zandy Touch | `badge-zandy-touch.svg` | Gold fill, black text | Own-brand products only |
| Sold out | `badge-sold-out.svg` | Ivory fill, dark text, grey border | Card and product page; also dims the photo slightly |
| Featured | `badge-featured.svg` | White fill, deep gold text and border | Admin list only |
| Low stock | `badge-low-stock.svg` | Warning tint fill, warning text and border (`#7A4F00` on `#FBEBC8`, 6.05:1) | Admin product list and StockPanel only (5 or fewer, spec Q15). Never shown to customers |
| Draft notice | `badge-draft-notice.svg` | Warning tint, 4px warning bar on the left, sentence case 13px "Draft text, not yet reviewed" (a sentence, so not uppercase) | Top of a policy page and the checkout order-terms panel while `reviewStatus` is `placeholder`; gone once approved. In HTML pair it with the `warning` icon and `role="note"` |

Admin status pills (`status-*.svg`; pill radius, Inter 600 13px sentence case, same-hue text and border on a tint): Draft and Placed use info (dark text on `#ECE5D4`), Archived uses muted text on the same tint, Confirmed and Out for delivery use warning, Published and Delivered use success, Cancelled uses error. Every pill carries its word, never colour alone. The admin should build pills as HTML from the `--pill-*` tokens; the SVGs are reference artwork.

Removed from the earlier set: the **Pay on delivery badge** (not in the revised spec; the three reassurance lines with icons replace it). Still not made on purpose: Sale (not in v1, spec Q9; a style is reserved: white on `--color-error`, 6.6:1), Free delivery (fee is negotiated) and Secure checkout (no online payment). If the owner wants any of these, raise it first.

Stock number styling (admin): count text is `--color-text`; at or below the low threshold it uses `--stock-low-text` beside the Low stock badge; at 0 it uses `--stock-out-text` beside the Sold out badge. In StockMovementList the change column prints `+12` in `--stock-increase-text` or `-3` in `--stock-decrease-text`, always with the sign, with quantity after in normal text.

## 7. Imagery

### Rules for the site
- Every product photo: portrait **4:5** (`--image-ratio-product`), subject centred, on a plain light backdrop. Category tiles are square 1:1, hero is 4:5 on phones and a 3:2 crop on desktop.
- Show photos with `object-fit: cover` for 4:5 images; if a photo is any other shape the frontend letterboxes it on `--image-bg` (white) rather than cropping the product.
- No text, prices, stickers, watermarks, phone numbers or claims inside the image. No logo overlay added in editing.
- No water splashes, glows, heavy filters, collages or AI-generated images of products or people. The photograph must show the real product the customer will receive. Retouching is limited to cropping, straightening and light brightness.
- People images (hero, about) need written permission from the model.
- Alt text is written per image in the admin (what the product is, colour and size), not the file name.
- Delivery sizes (for the backend to generate; quoted so the data cost stays low): 480px wide for grid cards (target 40 KB or less as WebP), 800px for the product page (80 KB), 1200px for zoom (150 KB). Hero 1200px wide (120 KB). The original upload is kept only as a source. This is the single biggest saving on mobile data.
- Placeholder when a photo is missing: flat `--image-placeholder-bg` with the product name in small text and the words "Photo coming soon". Never a stock or random-photo service.

### What the owner must supply (the images the site needs)
1. **Vector master of the logo** (SVG, AI, PDF or the original design file), to replace the recreation.
2. **One main photo per product** (front, label facing the camera), 4:5 portrait. The sample photos in `business/images/products/` show the problem: they are posters, social posts and web screenshots with text on top.
3. **Up to 3 extra photos per product** where useful: back or ingredients panel (readable), product open or texture (cream, soap, tea), and a scale shot (held in a hand). Maximum 6 per product (spec gallery limit).
4. **Six category tile photos** (Skincare, Perfumes, Body-shaping, Body care, Weight loss, Health and Wellness), 1:1, one hero product or a tidy group of 2 to 3 of the same range each. Until supplied, tiles use the category icon on ivory.
5. **One hero image** for the home page: Zandy Touch own-brand products on a neutral or ivory surface, 4:5 portrait with empty space at the top (for the logo or headline in HTML).
6. **One About image**: the shop front or the owner at work (optional but strongly helps trust). With consent.
7. **One share image** (1200x630 landscape) for WhatsApp and social link previews: the lockup on black. I can supply this from the logo once the vector master arrives.
8. **Own-brand product shots first.** The Zandy Touch range (for example the Brightening Cream, the soap, the porridge, the Brighter Glow package) matters most; third-party products come after.

### Seed images (TEMPORARY, until the owner supplies real photos)
business-context.md says the backend seeds sample products with public-domain or CC0 images. They exist only so the shop can be demonstrated and tested. They are **not** the shop's photography and must be replaced, but they must still follow the rules above so layout and the final photos look the same.

Choosing:
1. **Licence:** CC0 or public domain only (Wikimedia Commons PD or CC0 files, Openverse filtered to CC0 and Public Domain Mark). Not CC BY, not "free with credit". Record file URL, author, licence and date retrieved for every image, even though CC0 needs no credit.
2. **Pick files by name, not at random.** Search for the specific object ("cosmetic jar", "perfume bottle", "bar of soap", "measuring tape", "tea box"), save the chosen file in the repository's seed folder. Never hotlink and never use a random-photo URL service.
3. **Subject:** one object matched to the product's category and type (a jar for a cream, a bottle for a perfume). If nothing fits honestly, use the placeholder (`--image-placeholder-bg`, product name, "Photo coming soon") rather than a wrong product. Seed names are generic, so no recognisable third-party brand label should be legible.
4. **Backdrop:** plain white or very light cream, evenly lit, no table edge, shelf or clutter. Reject dark, coloured, patterned or outdoor scenes. Do not cut the object out and paste it on white (it looks fake); pick another file. Straightening and mild brightness only.
5. **Reject:** visible text, prices, watermarks, logos, hands or faces (no model release), screenshots, collages, AI-generated images, and anything implying a medical or weight-loss result. For Weight loss and Health and Wellness use neutral objects (tape measure, scale, tea or supplement jar), never body photos.
6. **Resolution:** at least 800 x 1000 pixels after cropping; smaller sources look soft at the 800px product-page size.

Cropping:
- Crop to **4:5 portrait** (`--image-ratio-product`), subject centred, filling 70 to 80 percent of the height with even margins. If 4:5 would cut the object, extend the canvas with the backdrop colour only when the original backdrop is one flat colour; otherwise pick another file.
- Export at the real delivery sizes (480, 800, 1200 px wide) so page weight and layout match production.
- Category tiles use the category icon on ivory in seed mode, not a seed photo.
- Alt text describes what is pictured ("White cosmetic jar on a white background"), written per image.

Marking as temporary: files are named `seed-<sku>-1.jpg` (SKU starts `SEED-`) and the database flags them as seed images so staff can find them. A seed image replaces nothing real: when the owner uploads a photo for a SKU it takes over and the seed file is deleted. No product in the live shop should still use a seed image at launch.

### Shot spec a non-photographer can follow on a phone
Setup (about 10 minutes for the whole setup, then 1 minute per product):
1. **Backdrop:** a clean white or very light cream sheet of thick paper, card or fabric (poster board costs a few thousand shillings). Curve it from table to wall so there is no line behind the product. No patterns, no shop shelves, no hands in the background.
2. **Light:** daylight from a window or open door, beside the product, in the shade (not direct sun). Switch off the camera flash and the room lights. If shadows are hard, hold a white sheet on the side opposite the window.
3. **Phone:** portrait orientation, held on a stack of books or a cheap tripod at the height of the middle of the product, level (use the grid lines in the camera settings). Wipe the lens. Use the normal 1x camera, not digital zoom. Tap the product on screen to focus.
4. **Framing:** product in the centre, filling about 70 to 80 percent of the height, with equal space around it. Leave a margin so the bottom and top can be trimmed to 4:5 without cutting the product. Label facing straight to the camera and readable.
5. **Groups:** same product shown in a group only on category and hero images. For product photos, one product (or one set sold together) only.
6. **Consistency:** same backdrop, same window, same time of day, same distance for the whole batch. Consistency matters more than a "perfect" photo.
7. **Do not:** add filters, beauty mode, HDR, text or stickers; shoot reflective bottles head-on at a window (turn the product slightly so the window is not reflected); photograph a screen or a poster.
8. **Check before upload:** is the label readable? Is the product sharp (zoom in to check)? Is the whole product inside the frame?

File and upload rules (match the admin bulk upload, spec A9):
- Format JPEG (or PNG or WebP), at least 1200 x 1500 pixels, **no larger than 5 MB each**, up to 20 files per upload.
- Name the file starting with the product SKU so the admin can match it: `ZT-SKIN-001-1.jpg` for the main photo, `-2`, `-3` for extras. No spaces.
- One folder per batch on the phone; upload on Wi-Fi where possible.

## 8. Components and states

Notation: tokens in backticks. "Light" means on ivory or white; "dark" means on black. Focus is always visible: 3px outline in `--focus-ring-color` (black on light, `--focus-ring-color-on-dark` light gold on dark), 2px offset, and never removed. Hover applies only on devices that hover; touch devices use the pressed state. Colour is never the only signal.

| Component | Default | Hover | Focus | Disabled | Error |
|---|---|---|---|---|---|
| Primary button (light) | Black fill, gold 600 label, 48px high, 8px radius | Fill `--button-primary-bg-hover`, label light gold | Focus ring | Flat `--button-disabled-*`, no hover, reason in text ("Sold out", "Set the delivery fee first") | Not used. Show the error beside the button |
| Primary button (dark) | Gold fill, black label | Light gold fill | Light-gold ring | Same flat style on dark: dark grey fill, muted text | n/a |
| Secondary button | Transparent, 2px black border, black label (gold on dark) | Tinted fill `--button-secondary-bg-hover` | Focus ring | Border and label in disabled colours | n/a |
| Text link | `--link-color`, underlined always (gold on dark) | Underline thickens, colour `--link-color-hover` | Focus ring | Not used | n/a |
| Text field, select | White fill, 1px `--field-border`, 8px radius, 17px text, label above | Border darkens to `--field-border-hover` | 2px black border plus ring | `--field-bg-disabled`, muted text, no ring | 2px `--color-error` border, warning icon, message below in `--color-error`, `aria-describedby` |
| Checkbox, radio | 24px box, 2px `--field-border`, label to the right, whole row tappable (48px) | Border darkens | Ring around the box | Muted box and label | Error border and message below the group |
| Quantity stepper | Pill, minus and plus buttons 48px, number between; hint "Up to N per order" when `maxQuantity` is below 10 | Button tinted | Ring on each button | Minus disabled at 1, plus at `maxQuantity` | Inline StockNotice if stock cannot cover it |
| Product card | Raised surface, 12px radius, `--shadow-1`, 4:5 photo, name, brand line, price | `--shadow-2`, name underlined | Ring around the whole card | Sold out (stock 0): photo dimmed to 70 percent, Sold out badge. Low stock is never shown to customers | n/a |
| Badge | See section 6 | Not interactive | n/a | n/a | n/a |
| Filter chip | Pill, 1px border, 17px text | Tinted fill | Ring | Muted | n/a |
| Selected chip | Black fill, ivory text | Same | Ring | n/a | n/a |
| Nav and header | Black, gold hairline below, ivory links | Links turn gold | Light-gold ring | n/a | n/a |
| Notice (inline, toast) | Tinted background, left 4px border, icon plus text in the semantic colour; `aria-live` | n/a | n/a | n/a | Error variant: `--color-error-bg`, error icon |
| Agreement panel | Raised card, scrollable text, checkbox row | Checkbox row tinted | Ring | n/a | Red border, message "Please accept the order terms to place your order" |
| Dialog | White panel on 60 percent black scrim, 12px radius, `--shadow-3` | n/a | Focus trapped, first control focused | n/a | n/a |
| StockPanel (admin) | Raised card: `stock` icon, count in 600 weight, status badge (none when available, Low stock or Sold out), "Adjust stock" secondary button, recent movements below | Button tinted | Ring on the button | Count is read-only text, never a field | Load failure: error notice with "Try again" |
| StockAdjustDialog (admin) | Dialog (below): mode radios (Add stock, Remove stock, Set to counted number), quantity field, reason select, note | As fields | As fields | Save disabled until mode, quantity and reason are valid, with the reason in text | Field errors as text fields; a conflict (stock changed meanwhile) is an error notice showing the new count and asking to confirm again |
| StockMovementList (admin) | Table, stacked rows on phones: `history` icon in the header, type, signed change (`+12` green, `-3` red, always with sign), quantity after, reason, order link, who, when | Row tinted, order link underlined | Ring on links | n/a | n/a |
| StockNotice (customer, `aria-live`) | Info or warning notice in words ("Only 3 available", "Up to 4 per order"); a number appears only in these limit messages | n/a | Ring on its action button ("Change to 3") | n/a | Sold out variant: error tint, "Sold out, remove to continue" |
| Draft notice | Badge `badge-draft-notice`: warning tint, left bar, "Draft text, not yet reviewed" | n/a | n/a | n/a | n/a |
| Status pill | Tinted bg, same-hue text and border, label text (`--pill-*` tokens) | n/a | n/a | n/a | n/a |
| Placeholder image | Flat tint, centred name and "Photo coming soon" | n/a | n/a | n/a | n/a |

## 9. Do and don't

1. **Do** keep gold small and deliberate: rules, icons, one button type, the own-brand badge. **Don't** fill large areas with gold or put gold text on ivory or white (1.9:1). Use `--color-accent-text` there.
2. **Do** put every word in HTML and keep body text at 17px with `--color-text` on ivory. **Don't** use images with baked-in text, prices or claims (like the sample posters), and never set body text below 15px. Never show a stock count to customers; use Available, Sold out and "Up to N per order".
3. **Do** shoot every product on a plain light backdrop in 4:5 portrait with soft window light. **Don't** use water splashes, glows, collages, stock photos or any random-photo service. Seed images follow the same 4:5 plain-backdrop rule and are marked temporary.
4. **Do** make trust visible with plain delivery and payment wording and the pay-on-delivery and trust icons. **Don't** add "Secure checkout", "Free delivery", star ratings or "Sale" labels the business cannot honestly support.
5. **Do** use the tokens, the one Tabler icon family, and the two self-hosted fonts at the weights shipped (Playfair 600, Inter 400 and 600). **Don't** hard-code colours, mix in a second icon set, request 700 weights or italics, or load fonts from a CDN.

## 10. Conflicts, flags and open items

- **Gold "primary button" (design-spec says "gold-on-black").** Read as: black fill with gold label on light surfaces, gold fill with black label on black surfaces. Both are in the tokens. Tell me if the spec meant something else.
- **Black-and-gold luxury versus phones in daylight.** Not a true conflict, resolved by using black for header, footer and hero only and ivory for reading and shopping areas. Flagged because a fully black site would read worse outdoors and would make product photos on white look pasted in.
- **Badges.** Sale, Free delivery and Secure checkout are ruled out by design-spec.md and business-context.md, so they were not made. The Pay on delivery badge from the first version is removed because the revised spec no longer lists it (section 6).
- **Seed images versus quality.** CC0 photos rarely meet a 4:5 plain-backdrop spec. The rule is to reject rather than retouch, so some seed products may stay on the placeholder. Acceptable for temporary data.
- **Colour changes to the brief.** Gold changed from `#C9A24B` to `#D6A94F` (sampled), plus two derived shades. Documented in section 3.
- **Slogan colour.** Logo uses neutral grey-white, SVG uses ivory.
- **Category icons** and the favicon at 16px are best-effort and should be reviewed. The logo recreation (redrawn this revision) should be replaced by the owner's vector master.
- **Research gap.** ASOS, Sephora and Jumia pages could not be inspected (403 or timeout). Only Fenty Beauty was read directly.
- **Claims.** The sample posters make medical and guarantee claims (spec Q3). Nothing in this system supports claim styling (no "100% guaranteed" badges).

## 11. Serving the files (frontend and backend are separate servers)

The **frontend server** serves `design-tokens.css` and `assets/`; the backend does not. Recommended public paths, keeping the folder relationship unchanged:

| Project file | Public URL |
|---|---|
| `design-tokens.css` | `/static/design-tokens.css` |
| `assets/fonts/*.woff2` | `/static/assets/fonts/...` |
| `assets/logo/`, `assets/icons/`, `assets/badges/` | `/static/assets/logo/...`, `/static/assets/icons/...`, `/static/assets/badges/...` |
| `assets/favicon.svg`, PNG icons, `site.webmanifest` | `/static/assets/...`, linked explicitly from the page head (icon, apple-touch-icon, manifest); optionally also serve `favicon.svg` at `/favicon.svg` |

The three `@font-face` URLs in `design-tokens.css` are relative (`assets/fonts/x.woff2`), so they resolve under `/static/assets/fonts/` when the CSS is at `/static/design-tokens.css`. To re-point them (another prefix or host) edit only those three `url(...)` values; CSS variables cannot be used inside `@font-face`, so they are not tokenised. If fonts sit on a different origin from the CSS, send `Access-Control-Allow-Origin` on the font files. Suggested caching: fonts, icons, logo and badges `Cache-Control: public, max-age=31536000, immutable`; `design-tokens.css` versioned in its file name or a short max-age with revalidation. Product photos are not here; they come from the backend as described in section 7.

## 12. Files and helper scripts

- `design-tokens.css`, `visual-guide.md` (project root)
- `assets/logo/` (9 SVG), `assets/badges/` (14 SVG), `assets/icons/` (43 icons plus licence), `assets/fonts/` (3 woff2 plus 2 licences), `assets/favicon.svg`, PNG sizes, `assets/site.webmanifest`
- `scripts/design/contrast.py`: Python 3 standard library only, WCAG contrast check; exits non-zero on a failure. Run after any colour change.
- `scripts/design/sample_logo.py`: re-samples gold and field colours from the logo JPEG (needs Pillow in a throwaway venv, not a project dependency).
- `scripts/design/build_assets.py`: regenerates logo, favicon, badge and status pill SVGs, converting text to outlines. Needs fontTools and brotli in a throwaway venv outside the project.
- `scripts/design/render_pngs.sh`: renders the PNG icons from SVG with Inkscape.
- Fonts and icons were downloaded once from the npm mirror on jsdelivr (Fontsource 5.3.0, @tabler/icons 3.49.0) and committed as static files. No runtime CDN, no build step, no libraries added to the project.
