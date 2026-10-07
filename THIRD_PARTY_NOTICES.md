# Third-Party Notices

Look4Sat-BA7OPF (this fork) bundles or adapts the following third-party
components:

## OrbitDeckiOS

- Source: https://github.com/prstoetzer/OrbitDeckiOS
- Copyright (c) 2025 Paul Stoetzer, N8HM
- License: MIT
- Used for: the CAT and antenna-rotator architecture, protocol command formats,
  related test vectors, and the Grid Finder VUCC grid line / grid point geometry
  in `core/domain/.../utility/GridGeometry.kt`.

### MIT License

```
MIT License

Copyright (c) 2025 Paul Stoetzer, N8HM

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

## Material Design Icons

- Source: https://materialdesignicons.com (Pictogrammers)
- License: Apache-2.0
- Used for: `core/presentation/src/main/res/drawable/ic_grid_finder.xml`
  (crosshairs-gps icon). Attribution is also noted in the icon file header.

## OpenStreetMap (award boundary geometry)

- Source: https://www.openstreetmap.org — relations `Ural Mountains` (21368163),
  `England` (58447), `Scotland` (58446), `Wales` (58437),
  `Northern Ireland` (156393)
- License: Open Database License (ODbL) 1.0 —
  https://opendatacommons.org/licenses/odbl/1-0/
- Used for: the Europe/Asia split line of European/Asiatic Russia and the
  England/Scotland/Wales/Northern Ireland partitions in
  `feature/map/src/main/assets/awards/dxcc.json` (derived geometry).
  Map tiles displayed in the app are © OpenStreetMap contributors and carry
  their own in-app attribution.

## Natural Earth

- Source: https://www.naturalearthdata.com (1:110m countries, 1:10m admin-0
  map units, 1:10m rivers)
- License: Public domain — https://www.naturalearthdata.com/about/terms-of-use/
- Used for: the country/region polygons of the award boundary assets under
  `feature/map/src/main/assets/awards/` (including the island / microstate
  DXCC entities — Singapore, Malta, Canary Is., ... — and the Balearic/Canary
  rings split out of Spain's 1:10m multipolygon), and the Ural river centerline
  used for the European/Asiatic Russia split.
