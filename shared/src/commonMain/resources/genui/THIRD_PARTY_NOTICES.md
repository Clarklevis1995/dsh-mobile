# Third-party notices

## Local native packaging modifications

This app vendors the official `@changfenhuang/dsh-genui@0.17.0` standalone
runtime, ECharts core bundle, and icon bundle for offline rendering. The
upstream MIT license is included in `LICENSE`.

### Upstream package and SHA-256 checksums

- Upstream npm package: `@changfenhuang/dsh-genui@0.17.0`
- `echarts-core.js` (verbatim copy of `lib/assets/echarts-core.js`):
  - SHA-256: `5eed01ed23a0246559d14f59c2ff5fc1b4fcca13558c2ed763a73724cd2b01e0`
- `icons.js` (verbatim copy of `lib/assets/icons.js`):
  - SHA-256: `1b2bb5c7256fb28de549cf0f43fa908dc8ae674ed4d6d13006909f9995aa1fe1`
- `standalone-runtime.js` (patched from `lib/assets/standalone-runtime.js`):
  - Upstream SHA-256: `59f8f45789d53132fd3f210027decdc89b129290eb3866ad18274be2f1d00f17`
  - Vendored SHA-256: `be936e1e321a880b19b191f278af2e331ed15b87b6b476d388eddc95234e2572`

### Reproducible modifications to `standalone-runtime.js`

Only two lines of `lib/assets/standalone-runtime.js` (three targeted
substitutions) are modified so the standalone bundle can forward actions to the
native WebView bridge while keeping all other runtime behavior identical to
upstream `0.17.0`:

1. **Line 318 (button click payload)** — include optional `text` and `details`
   on button action payloads:
   - Replace: ``onClick:o?()=>n(a,{type:`button`,label:e.label}):void 0``
   - With: ``onClick:o?()=>n(a,{type:`button`,label:e.label,text:e.text,details:e.details}):void 0``
2. **Line 318 (button schema guard)** — retain bounded optional `text` and
   `details` strings (max 8,192 chars) when normalizing `button` nodes:
   - Replace: ``...K(`action`,W(r.action,200))}}case`input`:``
   - With: ``...K(`action`,W(r.action,200)),...K(`text`,W(r.text,8192)),...K(`details`,W(r.details,8192))}}case`input`:``
3. **Line 1512 (standalone action provider)** — wire `GenuiActionContext`
   (`Ys.Provider`) to `window.GenuiNative.action` instead of `void 0`:
   - Replace: `Ys.Provider,{value:void 0,children:`
   - With: `Ys.Provider,{value:(action,payload)=>window.GenuiNative&&window.GenuiNative.action(action,payload),children:`

To replay these modifications on a clean `lib/assets/standalone-runtime.js`
from `@changfenhuang/dsh-genui@0.17.0`:

```sh
node -e '
const fs = require("fs");
let s = fs.readFileSync("lib/assets/standalone-runtime.js", "utf8");
const reps = [
  ["onClick:o?()=>n(a,{type:`button`,label:e.label}):void 0",
   "onClick:o?()=>n(a,{type:`button`,label:e.label,text:e.text,details:e.details}):void 0"],
  ["...K(`action`,W(r.action,200))}}case`input`:",
   "...K(`action`,W(r.action,200)),...K(`text`,W(r.text,8192)),...K(`details`,W(r.details,8192))}}case`input`:"],
  ["Ys.Provider,{value:void 0,children:",
   "Ys.Provider,{value:(action,payload)=>window.GenuiNative&&window.GenuiNative.action(action,payload),children:"],
];
for (const [from, to] of reps) {
  if (!s.includes(from)) throw new Error("Missing anchor: " + from);
  s = s.replace(from, to);
}
fs.writeFileSync("standalone-runtime.js", s);
'
```

Mermaid and three.js are not included in this native package; blocks requiring
those engines (`mermaid`, `scene3d`) fail closed in KMP to their original
Markdown code fence. `map` nodes render using the built-in offline coordinate
map view without MapLibre or network requests.

## React, ReactDOM, and scheduler (MIT)

The standalone runtime bundles React 18.3.1, ReactDOM 18.3.1, and scheduler.
Their embedded license banners identify Facebook, Inc. and its affiliates as the
copyright holder.

```
MIT License

Copyright (c) Facebook, Inc. and its affiliates.

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## KaTeX 0.16.47 (MIT)

KaTeX CSS and fonts are embedded in the official standalone runtime.

```
The MIT License (MIT)

Copyright (c) 2013-2020 Khan Academy and other contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Apache ECharts and ZRender (Apache-2.0 / BSD-3-Clause)

The ECharts core bundle (`echarts-core.js`, built by `@changfenhuang/dsh-genui@0.17.0`
with `echarts` `^5.6.0`) is vendored alongside the runtime for offline charts.
It includes Apache ECharts, ZRender, and tslib helper code.

- Complete Apache License 2.0 text (including Appendix): `LICENSE-APACHE-2.0.txt`
- Upstream Apache ECharts attribution notice: `NOTICE`

```
Apache ECharts
Copyright 2017-2024 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

### Apache ECharts subcomponents: d3.js (BSD-3-Clause)

Per Apache ECharts 5.6.0 `LICENSE`, portions of ECharts (`src/chart/treemap/treemapLayout.ts`,
`src/chart/tree/layoutHelper.ts`, `src/chart/graph/forceHelper.ts`, `src/util/number.ts`)
embed code from [d3.js](https://github.com/d3/d3) under the BSD 3-Clause License:

```
Copyright 2010-2016 Mike Bostock
All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of the author nor the names of contributors may be used to
  endorse or promote products derived from this software without specific prior
  written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### ZRender (BSD-3-Clause)

Bundled inside `echarts-core.js` with its embedded copyright banner:

```
BSD 3-Clause License

Copyright (c) 2017, Baidu Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of the copyright holder nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### tslib (0BSD)

`echarts-core.js` also retains the embedded `tslib` 0BSD license header
(`Copyright (c) Microsoft Corporation`).

## OpenAI Apps SDK UI icons (MIT)

The icon glyphs bundled in `src/client/icons/icon-data.ts` are copied from the
OpenAI Apps SDK UI icon set:

- Source: <https://github.com/openai/apps-sdk-ui>
- Pinned commit: `0f00143c7a639906f1621fe58e1b6be7b5bea46d`
- Upstream path: `src/components/Icon/svg`
- Vendored as: literal SVG geometry and attributes (no upstream component code), curated to 749 glyphs (64 inlined, 685 lazy-loaded); six glyphs with unsupported nested structures are excluded in full and listed in the upstream `ICON-NAMES.md`

```
Copyright 2025 OpenAI

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

The colour values referenced by the `--dsl-g-*` token layer are not copied from
that project: they resolve through the host's `--dsw-alias-*` tokens, and the
literals in `GenuiBlock.module.css` are last-resort fallbacks.
