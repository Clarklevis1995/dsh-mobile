# DeepSeek Harness iOS UI direction

## Product structure

- Workspace: project selector, recent sessions, active task state, primary new-session action.
- Conversation: native mobile reading flow, compact thought/tool-call events, code blocks, persistent composer.
- Trace: duration overview, model/tool timeline, event list, inspectable event detail sheet.

## Visual language

- Brand atmosphere: deep navy (`#07182B`), subtle grid, pixel particles, and a blurred whale-like light trail.
- Content surfaces: warm white (`#F7F8FA`) with ink (`#101318`) and cool gray (`#7D8592`) text.
- Accent: ocean blue (`#2E6BE6`) and mist blue (`#BFD4FF`). Purple and orange are reserved for trace semantics.
- Liquid glass: used selectively for navigation, segmented controls, the composer, bottom tab bar, primary action, and trace detail sheet. Long-form content stays opaque for readability.
- Glass construction: background blur, mild refraction, a one-pixel inner highlight, soft navy-tinted shadow, and pressed-state scale feedback.
- Type: SF Pro-style system typography; tabular figures for time, token, and throughput metrics.

## Interaction notes

- The desktop sidebar becomes a workspace home screen rather than a hidden drawer.
- Conversation and trace remain sibling views under one glass segmented control.
- Trace selection opens a draggable bottom sheet, preserving event-list context.
- Bottom navigation has three destinations: 工作区、会话、设置.
- Minimum touch target: 44 × 44 pt. Preserve safe-area spacing around Dynamic Island and home indicator.

## Final image-generation prompt

Create a high-fidelity iOS UI presentation with three straight-on iPhone screens: workspace/home, active agent conversation, and execution trace detail. Extend the supplied DeepSeek Harness website and WebUI language with deep-ocean navy atmosphere, technical grid and pixel particles, warm-white high-readability content surfaces, restrained ocean-blue accents, compact technical information design, and native iOS Liquid Glass navigation and controls. Use glass selectively for the workspace selector, new-session action, floating tab bar, top navigation, segmented conversation/trace control, composer, send control, and trace detail sheet. Preserve Chinese product copy and realistic trace metrics. Avoid desktop sidebars, generic blue-purple AI gradients, illegible text, extra logos, and watermarks.

Generated with the built-in ImageGen workflow using the four supplied screenshots as visual/product references only.
