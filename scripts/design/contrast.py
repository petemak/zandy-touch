#!/usr/bin/env python3
"""WCAG 2.1 contrast checker for the Zandy Touch palette (stdlib only).
Run: python3 scripts/design/contrast.py   (exit 1 if any pair fails)
"""
import sys
C = dict(black="0B0B0B", black2="1A1814", gold="D6A94F", goldlight="F4CC6A",
 golddeep="8A6310", ivory="F6F0E4", ivory2="FBF8F1", white="FFFFFF",
 text="1A1713", muted="5C5446", muteddark="B8AE9C", line="8C826F",
 success="1E6B3B", successbg="E3F1E7", warning="7A4F00", warningbg="FBEBC8",
 error="B42318", errorbg="FBE4E1", successdark="6FCF8E", errordark="FF8A80",
 warningdark="F4CC6A", focuslight="0B0B0B", hoverdark="26221B", infobg="ECE5D4", disabledbg="E4DCCB", disabledtext="5F574A", fielddis="ECE5D4", accent2="C29A43")
def lum(h):
    r,g,b=[int(h[i:i+2],16)/255 for i in (0,2,4)]
    f=lambda c: c/12.92 if c<=0.03928 else ((c+0.055)/1.055)**2.4
    return 0.2126*f(r)+0.7152*f(g)+0.0722*f(b)
def ratio(a,b):
    la,lb=sorted((lum(C[a]),lum(C[b])),reverse=True); return (la+0.05)/(lb+0.05)
# (foreground, background, minimum, description)
P=[
("text","ivory",4.5,"body text on page surface"),
("text","ivory2",4.5,"body text on card"),
("text","white",4.5,"body text on white (inputs)"),
("muted","ivory",4.5,"secondary text on surface"),
("muted","ivory2",4.5,"secondary text on card"),
("ivory","black",4.5,"body text on black header/footer"),
("muteddark","black",4.5,"secondary text on black"),
("muteddark","black2",4.5,"secondary text on raised dark"),
("gold","black",4.5,"gold text/links on black"),
("gold","black2",4.5,"gold text on raised dark"),
("goldlight","black",4.5,"hover gold on black"),
("black","gold",4.5,"button label: black on gold"),
("black","goldlight",4.5,"button hover: black on light gold"),
("gold","black",3.0,"gold icon / rule / focus ring on black"),
("golddeep","ivory",4.5,"gold-toned link/text on surface"),
("golddeep","white",4.5,"gold-toned text on white"),
("gold","black",4.5,"primary button label: gold on black (light-surface button)"),
("line","ivory",3.0,"input border / UI boundary on surface"),
("line","white",3.0,"input border on white"),
("black","ivory",3.0,"focus ring on light surface"),
("success","ivory",4.5,"success text on surface"),
("success","successbg",4.5,"success text on its tint"),
("warning","ivory",4.5,"warning text on surface"),
("warning","warningbg",4.5,"warning text on its tint"),
("error","ivory",4.5,"error text on surface"),
("error","white",4.5,"error text on white field"),
("error","errorbg",4.5,"error text on its tint"),
("successdark","black",4.5,"success text on black"),
("errordark","black",4.5,"error text on black"),
("warningdark","black",4.5,"warning text on black"),
("black","ivory",4.5,"Sold out / New badge text if black on ivory"),
("ivory","black",4.5,"badge: ivory on black"),
("white","error",4.5,"white on error (reserved Sale badge)"),
("goldlight","hoverdark",4.5,"primary button hover label: light gold on dark hover"),
("gold","hoverdark",4.5,"gold label on hovered dark button"),
("text","infobg",4.5,"info text on its tint / secondary hover"),
("black","accent2",4.5,"primary-on-dark pressed state"),
("disabledtext","disabledbg",4.5,"disabled label (exempt, kept readable)"),
("disabledtext","fielddis",4.5,"disabled field text (exempt, kept readable)"),
("line","ivory2",3.0,"control border on card"),
("goldlight","black",3.0,"focus ring on black"),
("black","ivory2",3.0,"focus ring on card"),
("muted","infobg",4.5,"muted text on tint (Archived pill)"),
("text","infobg",4.5,"Draft / Placed pill text on tint"),
("success","white",4.5,"stock increase (+N) on white table row"),
("success","ivory2",4.5,"stock increase on card"),
("error","ivory2",4.5,"stock decrease (-N) on card"),
("warning","white",4.5,"low-stock count on white table row"),
("warning","ivory2",4.5,"low-stock count / draft notice text on card"),
("warning","warningbg",3.0,"Low stock / draft notice border on its tint (UI boundary)"),
("success","successbg",3.0,"Published / Delivered pill border on tint"),
("error","errorbg",3.0,"Cancelled pill border on tint"),
("text","ivory2",3.0,"Draft pill border on card"),
("gold","black",4.5,"New badge: gold text on black"),
("golddeep","white",4.5,"Featured badge text on white"),
("text","ivory",4.5,"Sold out badge text on ivory"),
]
bad=0
print(f"{'foreground':<23}{'background':<23}{'ratio':>7} {'min':>4}  result  use")
for f,b,m,d in P:
    r=ratio(f,b); ok=r>=m; bad+=not ok
    print(f"{f+' #'+C[f]:<22} {b+' #'+C[b]:<22}{r:>5.2f}:1 {m:>4}  {'PASS' if ok else 'FAIL'}  {d}")
sys.exit(1 if bad else 0)
