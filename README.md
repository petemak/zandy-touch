# Property Website Builder

## Introduction

This project uses a multi-agent team that takes a business context file describing a botique and turns it into a branding, design, a working front-end and back-end. The outcome is a real deployment that customers or clients can use to consume services provided by the business or organisation .

NOTE: requires a Claude Pro subscription to use the multi-agent feature

## The Agent Team
A team of 5 agents starting from a branding expert to the deploymwnt engineer waiting for a prompt to kick off work on a project.

- Branding specialist
- UX / Web designer
- Architect
- Frontend developer
- Backend developer
- QA engineer
- Deployment Engineer


### The agents
.claude
├── agents
│   ├── architect.md
│   ├── backend-clojure.md
│   ├── backend-node.md
│   ├── brand-strategist.md
│   ├── deployment-engineer.md
│   ├── frontend-cljs.md
│   ├── frontend-html.md
│   ├── qa.md
│   ├── ux-designer.md
│   └── visual-designer.md
└── settings.json


### Supporting files
.
├── business
│   └── images
│       ├── logo
│       └── products
│           ├── assortment-perfumes.jpeg
│           ├── beauty-milk.jpeg
│           ├── dietary-supplements-magnesium-complex.jpeg
│           ├── gluta-koji-soap.jpeg
│           ├── slimma-sleaming-tea.jpeg
│           ├── zandy-brighter-glow-cream.jpeg
│           ├── Zandy-brigtening-cream.jpeg
│           └── zandy-weight-gain-porridge.jpg
├── business-context.md
├── engineering-standards.md
├── README.md
└── scripts
    ├── allowed-deps.edn
    └── check_deps.clj




## Kicking off the website creation
Since we have a team of agents, we just need a prompt to kick off work. The business idea does not need to be specified in the prompt.
The agents are designed to read the business idea from the _business-context.md_ file if provided.



## Evals
Build a small, fixed prompt set, See  eval-prompts.md 


