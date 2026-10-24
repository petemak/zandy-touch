# Zandy Touch Brand Brief

Source of truth: `business-context.md` (read in full). The real logo and several product sample photos were also viewed. This brief covers brand direction only. It does not cover layout, code or site copy.

## 1. Name and tagline

- **Name:** Zandy Touch (given). Domain: zandytouch.com (to be registered).
- **Tagline:** "Beauty with Confidence" (given, the existing slogan on the logo). Keep it as is.

## 2. Target audience

Urban Ugandan professionals in their early 20s to late 30s, mainly in and around Kampala. They like style and are willing to pay for quality products that are hard to find in the local market. They discover products through social media, referrals and word of mouth. They are loyal, want to hear when new products arrive, and order once they trust the brand from past experience. Most browse on a phone. Cards are uncommon, so they pay by mobile money or cash on delivery, and many still order by phone call or WhatsApp. A smaller second group is customers in other Ugandan cities, with neighbouring countries planned later.

## 3. Voice

Adjectives: **Elegant, Warm, Trustworthy, Confident.**

- Do: speak like a friendly, knowledgeable shop owner who is happy to help, with plain and polished sentences that say what a product does and who it suits.
- Don't: shout, use hard-sell hype, or make exaggerated medical or "guaranteed result" claims. The luxury feel comes from restraint, not from capital letters and exclamation marks.

Note on the source material: the sample posters use loud promotional lines ("Real Results", "Luxury Now Always"). Treat the web voice as calmer than the posters.

## 4. Color palette

Derived from the real logo, which is a black field with metallic gold. The gold hex values are estimates read by eye from the image, not sampled with a tool, so they should be checked against the original artwork.

| Role | Hex | Rationale |
|---|---|---|
| Black (primary background) | `#0B0B0B` | Matches the logo field and the "black-and-gold luxury" style in the context file. Near-black avoids a harsh pure `#000`. |
| Gold (primary accent) | `#C9A24B` | Reads as a mid, slightly muted gold. It keeps the logo's warmth without the glossy gradient. It has strong contrast on black for headings, rules and buttons. |
| Ivory (surface and text) | `#F6F0E4` | A warm off-white for body text on black and for light content areas. It is softer than pure white and echoes the peach and cream tones of the product photos. |

Usage notes:
- Gold on white or ivory has weak contrast, so don't use it for small text there. Use black text on ivory, and gold only for large elements or borders.
- The logo's glow, flare and gradient effects are rendering effects. Use flat gold in the site and keep any gradient subtle.
- Don't adopt the purple and green from the weight-gain porridge poster as brand colors. See Discrepancy 4.

## 5. Typography direction

- **Headings:** Playfair Display (high-contrast serif, close in spirit to the "Zandy Touch" wordmark). Alternative: Cormorant Garamond for a lighter look.
- **Body:** Inter. It is very legible on small mobile screens and low-bandwidth devices.
- **Tagline and small labels:** use Inter in wide-tracked uppercase, which echoes the spaced "BEAUTY WITH CONFIDENCE" line on the logo. This is a styling choice, not a third font.
- Both are free Google Fonts. Self-hosting or limiting the weights keeps loading light, which matters given the hosting budget.

Logo note: the context file says to keep the Z monogram, wordmark and slogan, recreate them as SVG, and leave out the phone number and category icons. I observed the following in the artwork:
- The Z monogram has a flowing swoosh and a small star.
- The wordmark is a high-contrast serif.
- The slogan is in spaced capitals between thin rules.

## 6. References and competitors

I could not fetch or inspect the live sites. The web search returned only general information (no page-level design detail). The notes below therefore combine the search results with general knowledge of these sites and with the owner's stated likes. Treat them as directional and check them against the live sites.

1. **Jumia Uganda (jumia.com.ug).** This is the closest local reference. It sells health and beauty products in Uganda and supports cash on delivery. Source: [ITWeb: Jumia launches in Uganda](https://itweb.africa/article/online-shopping-website-jumia-launches-in-uganda/kLgB17e843yv59N4).
   - Borrow: the familiar pay-on-delivery flow and clear delivery messaging.
   - Avoid: the crowded, discount-led marketplace look, which would undercut a luxury positioning.
2. **Sephora (sephora.com).** A luxury beauty retailer known for easy navigation and detailed product descriptions. Source: search result summary of Sephora's luxury beauty platform.
   - Borrow: category navigation and a clean product detail layout with a short description and benefits.
   - Avoid: heavy promotions banners, account and loyalty prompts (guest checkout is a requirement here), and a dense interface.
3. **Fenty Beauty (fentybeauty.com).** A brand site with a confident, inclusive voice and bold product photography. Source: [PowerCommerce case study](https://powercommerce.com/pages/case-study/fenty-beauty).
   - Borrow: product photography that puts the product and skin first, and confident but simple copy.
   - Avoid: its many-shade selector complexity, which this catalogue does not need.

Owner-named sites (from the context file), with the takeaway for each:
- ASOS: sleek design. Borrow the clean product grid and the clear information on each product.
- Etsy: intuitive commerce design. Borrow simple browsing and a plain cart.
- Glovo: bringing a variety of products to the door. Borrow clear delivery messaging.
- Walmart: efficiency and user experience. Borrow fast, low-friction ordering.

## 7. Discrepancies and ambiguities (recorded, not silently resolved)

1. **Prices: "negotiated" versus "set by the owner".** Under "Payments and delivery", "Prices are negotiated with the customer" sits next to "In the webshop, prices will be set by the owner". The "What we do" section says "Delivery costs are negotiated with the customer". I have read the first line as referring to **delivery prices**, with product prices fixed by the owner in the catalogue. This is an interpretation and the owner should confirm it. If product prices are in fact negotiable, the site would need a different approach, such as "price on request" or a negotiation step.
2. **Weight-gain porridge versus "Weight loss".** The weight-gain porridge does not fit the "Weight loss" product group. No group in the list covers it. It is arguably "Health & Wellness" (it is positioned as nutrition), but I have not decided. The owner needs to confirm the category, or add a group such as "Nutrition".
3. **"Bedroom" category.** The logo shows a "Bedroom" category (bed icon), which is not in the six-group product list. Some products may belong to it, such as intimate products. The owner needs to confirm whether it is a real product group. I have not added it to the brand structure.
4. **Brand variants in the samples.** The porridge poster uses a purple and green palette and a "Beauty, Wellness, You" descriptor. The perfume poster uses a sub-brand, "Zandy Luxe", with its own tagline. Both differ from the black-and-gold identity in the context file. The context file wins, so this brief follows black and gold. The owner should confirm whether Zandy Luxe is a separate brand or a retired variant.
5. **Phone number format.** The logo shows `0706105779`. The context file gives `+256706105779`. These are the same number in local and international form. Use the international form on the site.
6. **Third-party products in the samples.** Some samples show other brands (for example Slimma Tea and Pezo Mega). The context file says "established brands" are sold alongside own-brand products. The own-brand ("Zandy Touch") products are the ones to emphasise.
7. **Claims.** The context file describes products as "natural, organic and authentic". The samples include strong claims (skin brightening, hyperpigmentation clearing, weight loss, "no side effect"). The brand voice should avoid unverified medical or guarantee claims. The owner should confirm which claims can be supported. This is a flag, not a finding about the products.
8. **Current ordering versus the planned webshop.** Customers currently order by phone or WhatsApp. The webshop adds guest orders with payment on delivery. The WhatsApp number may be the same as the phone number, which is not stated.

## 8. Assumptions

`business-context.md` exists, so I did not need to make up a brief from a thin description. These are the points where I went beyond it:
- Hex values were chosen by eye from the logo image and not sampled. The ivory is my addition.
- Font choices are my recommendation. The context file names no fonts.
- The voice adjectives are inferred from the stated values (confidence, trust, professionalism, friendly service) and the logo's style.
- I assumed most customers browse on mobile phones, since that is typical for the market. The file does not say so.
- The competitor notes are not verified against the live sites (see section 6).
- I assumed the product photos are mood references only, as the context file says, and that final site photography will come later.
