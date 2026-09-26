# Changelog

## [3.62.0](https://github.com/yschimke/compose-ui-builder/compare/v3.61.0...v3.62.0) (2026-09-26)


### Features

* **ui-builder-export:** export A2UI palette designs as A2UI v0.9 messages ([#302](https://github.com/yschimke/compose-ui-builder/issues/302)) ([fa7ecf0](https://github.com/yschimke/compose-ui-builder/commit/fa7ecf0ad0ac443c9c2fba88721b631e4eea38ce))
* **ui-builder:** package the A2UI basic catalog ([#304](https://github.com/yschimke/compose-ui-builder/issues/304)) ([53590b6](https://github.com/yschimke/compose-ui-builder/commit/53590b6a6fec8ddf4a7dd5f1e93af364e20b5fb7))


### Bug Fixes

* **editor:** a round or rounded device preview shows the panel, not white, in its corners ([#300](https://github.com/yschimke/compose-ui-builder/issues/300)) ([36c637d](https://github.com/yschimke/compose-ui-builder/commit/36c637db18492c601af1415282a26f499365168c))
* **runtime:** keep owner-only design actions with the owner's own identity ([#303](https://github.com/yschimke/compose-ui-builder/issues/303)) ([2ce33e3](https://github.com/yschimke/compose-ui-builder/commit/2ce33e3964405ac1012f4c5c06b8bd230e6742a7))
* **ui-builder:** draw every catalog's palette thumbnails whole, as miniatures when they need room ([#307](https://github.com/yschimke/compose-ui-builder/issues/307)) ([e97153d](https://github.com/yschimke/compose-ui-builder/commit/e97153de80defcee726ad410c981820441cb5607))
* **ui-builder:** draw Wear viewports with the real ScreenScaffold, and size previews to the watch ([#306](https://github.com/yschimke/compose-ui-builder/issues/306)) ([1c24bc4](https://github.com/yschimke/compose-ui-builder/commit/1c24bc4a1abf22aef5f8d6198d3e1e4d11cc70b6))

## [3.61.0](https://github.com/yschimke/compose-ui-builder/compare/v3.60.0...v3.61.0) (2026-09-26)


### Features

* **ui-builder:** load the vendored fonts, pick the design's typeface, and name theme colours ([#292](https://github.com/yschimke/compose-ui-builder/issues/292)) ([6e3b220](https://github.com/yschimke/compose-ui-builder/commit/6e3b220b4bc27814697c93de72af78c97f22643e))


### Bug Fixes

* **ui-builder:** keep the edge button in the scaffold's slot, and the compact dock's labels on one line ([#298](https://github.com/yschimke/compose-ui-builder/issues/298)) ([e29abc4](https://github.com/yschimke/compose-ui-builder/commit/e29abc4a8b9adc89b7da3ff16f9d07a0aefdaf6e))


### Performance Improvements

* **ui-builder:** keep fonts off first load, and fetch them from the immutable bundle prefix ([#297](https://github.com/yschimke/compose-ui-builder/issues/297)) ([d36fc7c](https://github.com/yschimke/compose-ui-builder/commit/d36fc7ca6c0e625bf07a0d115caad44008cae3c8))

## [3.60.0](https://github.com/yschimke/compose-ui-builder/compare/v3.59.0...v3.60.0) (2026-09-26)


### Bug Fixes

* **ui-builder:** device previews draw with the catalog runtime again, and the selection card can be closed ([#293](https://github.com/yschimke/compose-ui-builder/issues/293)) ([3cb853a](https://github.com/yschimke/compose-ui-builder/commit/3cb853ae1e725c21d4c501f5af3aaf0beaa63f91))

## [3.59.0](https://github.com/yschimke/compose-ui-builder/compare/v3.58.0...v3.59.0) (2026-09-25)


### Bug Fixes

* **ui-builder:** declare the import map before the module preloads, so a reloaded editor starts ([#290](https://github.com/yschimke/compose-ui-builder/issues/290)) ([cab08cc](https://github.com/yschimke/compose-ui-builder/commit/cab08cc11b7fb062ebd21687c73e23b62e7104c7))

## [3.58.0](https://github.com/yschimke/compose-ui-builder/compare/v3.57.0...v3.58.0) (2026-09-25)


### Bug Fixes

* **ui-builder:** the pinned runtime frame fills its surface on a high-density screen ([#288](https://github.com/yschimke/compose-ui-builder/issues/288)) ([9be6efc](https://github.com/yschimke/compose-ui-builder/commit/9be6efc9ce3b21b823badd4a219b1a6db52abc07))

## [3.57.0](https://github.com/yschimke/compose-ui-builder/compare/v3.56.0...v3.57.0) (2026-09-25)


### Bug Fixes

* **ui-builder:** export m3/dialog through the component record, as AlertDialog ([#286](https://github.com/yschimke/compose-ui-builder/issues/286)) ([cf78874](https://github.com/yschimke/compose-ui-builder/commit/cf78874e5459d2cad2c10dc0e3ac5fdad5f73cbf))

## [3.56.0](https://github.com/yschimke/compose-ui-builder/compare/v3.55.0...v3.56.0) (2026-09-25)


### Features

* **ui-builder:** visual colour and enum property editors; replay a restore only for its own actor and revision ([#284](https://github.com/yschimke/compose-ui-builder/issues/284)) ([77518e8](https://github.com/yschimke/compose-ui-builder/commit/77518e81bd2f6e773d5be47cd61382bb3c698fcf))


### Bug Fixes

* **ui-builder:** an edit re-renders the live catalog runtime frame instead of rebooting it ([#283](https://github.com/yschimke/compose-ui-builder/issues/283)) ([2b9356b](https://github.com/yschimke/compose-ui-builder/commit/2b9356b897966c733d029b90aaff9f717b7447f5))

## [3.55.0](https://github.com/yschimke/compose-ui-builder/compare/v3.54.0...v3.55.0) (2026-09-25)


### Bug Fixes

* **ui-builder-export:** pad a Scaffold's content by the padding it is handed ([#279](https://github.com/yschimke/compose-ui-builder/issues/279)) ([2c84373](https://github.com/yschimke/compose-ui-builder/commit/2c8437382c53b2f58df591402cd0aa1d6dd92790))
* **ui-builder-runtime:** replay a retried restore, and name who made each revision after an upload ([#280](https://github.com/yschimke/compose-ui-builder/issues/280)) ([16bbbea](https://github.com/yschimke/compose-ui-builder/commit/16bbbea865a309ead0fb67cbf28ddc7bd204f2bf))
* **ui-builder:** take the boot screen away at ready, and package the renderer and fixtures from production Wasm ([#281](https://github.com/yschimke/compose-ui-builder/issues/281)) ([2f8c3bd](https://github.com/yschimke/compose-ui-builder/commit/2f8c3bd128df3e606f41cea9ef70e39f1d10a47a))

## [3.54.0](https://github.com/yschimke/compose-ui-builder/compare/v3.53.0...v3.54.0) (2026-09-25)


### Features

* **ui-builder:** public designs, restore, fork, a friendlier home screen; menus above device previews ([#278](https://github.com/yschimke/compose-ui-builder/issues/278)) ([4c2ff0c](https://github.com/yschimke/compose-ui-builder/commit/4c2ff0c7a5ed4334a1b9d2eac5d7cef6f589b1f9))


### Bug Fixes

* **ui-builder:** stop booting a runtime iframe per palette tile, and hide what cannot land ([#274](https://github.com/yschimke/compose-ui-builder/issues/274)) ([314b1f6](https://github.com/yschimke/compose-ui-builder/commit/314b1f68893fe242cee9081af7eeb35c9cda9848))


### Performance Improvements

* **ui-builder:** ship the production Wasm, preload it, and show a boot screen ([#276](https://github.com/yschimke/compose-ui-builder/issues/276)) ([2512f50](https://github.com/yschimke/compose-ui-builder/commit/2512f50dd5ca01238f778be8ea3cbd9f9d258a88))

## [3.53.0](https://github.com/yschimke/compose-ui-builder/compare/v3.52.0...v3.53.0) (2026-09-25)


### Bug Fixes

* **ui-builder-export:** name the pane scaffold value's strategies and history ([#273](https://github.com/yschimke/compose-ui-builder/issues/273)) ([96608fb](https://github.com/yschimke/compose-ui-builder/commit/96608fb0f3dd42cb8700c07ad42e21ea97de653a))

## [3.52.0](https://github.com/yschimke/compose-ui-builder/compare/v3.51.0...v3.52.0) (2026-09-25)


### Bug Fixes

* **ui-builder-export:** pass AppCard its content lambda when it has no body ([#271](https://github.com/yschimke/compose-ui-builder/issues/271)) ([f22e75d](https://github.com/yschimke/compose-ui-builder/commit/f22e75d1b765b81b1b414df416cd6fd669c555ce))

## [3.51.0](https://github.com/yschimke/compose-ui-builder/compare/v3.50.0...v3.51.0) (2026-09-25)


### Features

* **ui-builder:** pinch and Ctrl/Cmd + wheel zoom on the canvas, anchored under the hand ([#268](https://github.com/yschimke/compose-ui-builder/issues/268)) ([f9604a1](https://github.com/yschimke/compose-ui-builder/commit/f9604a1deb31b38860bc2319326612672b117681))
* **ui-builder:** unframed component palette, touch-aware drags and resize handles ([#264](https://github.com/yschimke/compose-ui-builder/issues/264)) ([1071a84](https://github.com/yschimke/compose-ui-builder/commit/1071a840e72100b1a024f68e75ff9de8f056cea2))
* **ui-builder:** Wear dialog defaults, button colours and a Wear render lane ([#263](https://github.com/yschimke/compose-ui-builder/issues/263)) ([20e28b0](https://github.com/yschimke/compose-ui-builder/commit/20e28b04cb1a84e0d3bb65ad7f5069d8d2f610a0))


### Bug Fixes

* **ui-builder:** read size and scale as Compose applies them, and snap Fill to the content edge ([#266](https://github.com/yschimke/compose-ui-builder/issues/266)) ([debe4d0](https://github.com/yschimke/compose-ui-builder/commit/debe4d028c8cbfad785df10cfe466185e9138900))
* **ui-builder:** the code pane and problems panel export widget pictures from fetched bytes ([#270](https://github.com/yschimke/compose-ui-builder/issues/270)) ([902870e](https://github.com/yschimke/compose-ui-builder/commit/902870edb26ef19998862429bfc3e8ae31ed433a))

## [3.50.0](https://github.com/yschimke/compose-ui-builder/compare/v3.49.0...v3.50.0) (2026-09-25)


### Features

* **remote-m3:** offer the core Remote Compose layouts (fit box, flow row, collapsibles) and sharedElement ([#256](https://github.com/yschimke/compose-ui-builder/issues/256)) ([2c895a9](https://github.com/yschimke/compose-ui-builder/commit/2c895a92c6365fc9c04597562a4ce30286c347ee))
* **ui-builder:** export all five Google samples, idiomatically, and fit them to a phone ([#239](https://github.com/yschimke/compose-ui-builder/issues/239)) ([a6fc556](https://github.com/yschimke/compose-ui-builder/commit/a6fc55668da845e92b532a39901b38d470f10c13))
* **ui-builder:** host bridge, chrome and theme, so an IDE webview can edit a design it owns ([#249](https://github.com/yschimke/compose-ui-builder/issues/249)) ([7a5cff8](https://github.com/yschimke/compose-ui-builder/commit/7a5cff884d42fbde560815cd95299351b2cca32c))
* **ui-builder:** navigation suite and scrollable tab row, with the Google designs on them ([#241](https://github.com/yschimke/compose-ui-builder/issues/241)) ([3ab1cf8](https://github.com/yschimke/compose-ui-builder/commit/3ab1cf8646d01dfbf754a5c53970429dee1f7383))
* **ui-builder:** upstream Wear sample screens as designs, with a guidance critique ([#254](https://github.com/yschimke/compose-ui-builder/issues/254)) ([ff19a0e](https://github.com/yschimke/compose-ui-builder/commit/ff19a0ec8d6a1492c35dc77c5226869986108050))


### Bug Fixes

* **ui-builder-export:** keep the native lane's screen name, and use the lists guide's modifier order ([#258](https://github.com/yschimke/compose-ui-builder/issues/258)) ([3e6f2c3](https://github.com/yschimke/compose-ui-builder/commit/3e6f2c38415dbaedacfe96bf227d61882be6a240))
* **ui-builder-export:** write Wear screens the way the Wear Material 3 guidance does ([#257](https://github.com/yschimke/compose-ui-builder/issues/257)) ([4837143](https://github.com/yschimke/compose-ui-builder/commit/4837143158a713782dd28cebb7d4b80e05977462))

## [3.49.0](https://github.com/yschimke/compose-ui-builder/compare/v3.48.0...v3.49.0) (2026-09-25)


### Bug Fixes

* **intellij:** reconnect a remote design's update stream after a disconnect ([#233](https://github.com/yschimke/compose-ui-builder/issues/233)) ([4ad58e9](https://github.com/yschimke/compose-ui-builder/commit/4ad58e9568b45ebc9d39dce4ac3a71a97170825b))
* **ui-builder:** catalog runtimes get the design's pictures and follow canvas zoom ([#240](https://github.com/yschimke/compose-ui-builder/issues/240)) ([df78358](https://github.com/yschimke/compose-ui-builder/commit/df783585dbc8ee558439fc07360e5be8f43922c7))

## [3.48.0](https://github.com/yschimke/compose-ui-builder/compare/v3.47.0...v3.48.0) (2026-09-24)


### Features

* **ui-builder-web:** ship a version and server-API manifest with the editor archive ([#228](https://github.com/yschimke/compose-ui-builder/issues/228)) ([5599203](https://github.com/yschimke/compose-ui-builder/commit/55992032c5476d3f134fd4454aa3c1aa2f7f1d86))


### Bug Fixes

* **ui-builder:** make the Google app samples lay out and export correctly ([#230](https://github.com/yschimke/compose-ui-builder/issues/230)) ([91589af](https://github.com/yschimke/compose-ui-builder/commit/91589aff356eb62ec2f563ad3e7e1d950f56101c))
* **ui-builder:** show remote-m3 widgets on the canvas and in PNG exports ([#232](https://github.com/yschimke/compose-ui-builder/issues/232)) ([6212bb8](https://github.com/yschimke/compose-ui-builder/commit/6212bb8f2f15299ce023211d11a43ddb3c29da9b))

## [3.47.0](https://github.com/yschimke/compose-ui-builder/compare/v3.46.0...v3.47.0) (2026-09-24)


### Features

* **ui-builder:** group the home screen's recent designs by folder ([#223](https://github.com/yschimke/compose-ui-builder/issues/223)) ([922780c](https://github.com/yschimke/compose-ui-builder/commit/922780c4e12c55affe629630842c3ab5a0ed62cf))


### Bug Fixes

* **ui-builder:** tell a catalog runtime which widget host shape each pane is ([#225](https://github.com/yschimke/compose-ui-builder/issues/225)) ([61e6837](https://github.com/yschimke/compose-ui-builder/commit/61e68372962cb04bc2c50c386516c3eb66e51708))

## [3.46.0](https://github.com/yschimke/compose-ui-builder/compare/v3.45.0...v3.46.0) (2026-09-24)


### Bug Fixes

* **ui-builder-export:** import the RemoteBox an empty widget's native preview draws ([#220](https://github.com/yschimke/compose-ui-builder/issues/220)) ([cdf00ab](https://github.com/yschimke/compose-ui-builder/commit/cdf00ab6c0bbab74bd3b2cd1c803c6860c43324e))
* **ui-builder:** let the runtime canvas show through the editor frame ([#221](https://github.com/yschimke/compose-ui-builder/issues/221)) ([47f7740](https://github.com/yschimke/compose-ui-builder/commit/47f774056d8e63aa3519be1afeec7f9fc95b21d4))

## [3.45.0](https://github.com/yschimke/compose-ui-builder/compare/v3.44.0...v3.45.0) (2026-09-24)


### Features

* **ui-builder:** experimental adaptive Wear widget container ([#219](https://github.com/yschimke/compose-ui-builder/issues/219)) ([c89ff70](https://github.com/yschimke/compose-ui-builder/commit/c89ff70bbec39133cb6045519920e5bf67ce123b))
* **ui-builder:** export a design as a stamped Figma scene ([#205](https://github.com/yschimke/compose-ui-builder/issues/205)) ([370ec42](https://github.com/yschimke/compose-ui-builder/commit/370ec4285e8838798a4572368a5bd46026850f65))
* **ui-builder:** import a Figma frame snapshot as a design operation log ([#204](https://github.com/yschimke/compose-ui-builder/issues/204)) ([e5ee8ac](https://github.com/yschimke/compose-ui-builder/commit/e5ee8ac9e19204a4ec84a475a94f351115660e05))
* **ui-builder:** turn Figma edits to an exported frame into a design command ([#206](https://github.com/yschimke/compose-ui-builder/issues/206)) ([394c391](https://github.com/yschimke/compose-ui-builder/commit/394c39142a15601d1f20831727910b61cf4896fd))


### Bug Fixes

* **ui-builder-export:** let the remote-m3 vocabulary name the Remote Material 3 components ([#214](https://github.com/yschimke/compose-ui-builder/issues/214)) ([1f3686e](https://github.com/yschimke/compose-ui-builder/commit/1f3686ef32759ecf968eb529941718c860241076))
* **ui-builder:** name the stadium host Samsung and the rounded rectangle Pixel Watch ([#213](https://github.com/yschimke/compose-ui-builder/issues/213)) ([e32a116](https://github.com/yschimke/compose-ui-builder/commit/e32a1162b5a241f42c57729e9b41e9c46d63389c))

## [3.44.0](https://github.com/yschimke/compose-ui-builder/compare/v3.43.0...v3.44.0) (2026-09-24)


### Features

* **ui-builder-export:** write Remote Material 3 components from an embedded record ([#202](https://github.com/yschimke/compose-ui-builder/issues/202)) ([54d6d56](https://github.com/yschimke/compose-ui-builder/commit/54d6d561e6366d359f4058dfd728f87607615852))
* **ui-builder-runtime:** offer Remote Material 3 components on the widget palette ([#207](https://github.com/yschimke/compose-ui-builder/issues/207)) ([85efc62](https://github.com/yschimke/compose-ui-builder/commit/85efc6202e6844eb12fd9455217ea2cd2b7091ec))


### Bug Fixes

* close review findings in the desktop host, local store and packaging CI ([#210](https://github.com/yschimke/compose-ui-builder/issues/210)) ([2a51134](https://github.com/yschimke/compose-ui-builder/commit/2a51134af6ff58e9e2bbdd166af3570c461a7206))
* **intellij:** close write and session-lifecycle races in the project design writer ([#211](https://github.com/yschimke/compose-ui-builder/issues/211)) ([58fabb4](https://github.com/yschimke/compose-ui-builder/commit/58fabb419b7bf8a29368ee4c5af567256a11434b))
* **ui-builder:** draw component catalog tiles so each component reads clearly ([#208](https://github.com/yschimke/compose-ui-builder/issues/208)) ([7110a64](https://github.com/yschimke/compose-ui-builder/commit/7110a6445b2c0fc8632155888ef5678787174c79))

## [3.43.0](https://github.com/yschimke/compose-ui-builder/compare/v3.42.0...v3.43.0) (2026-09-24)


### ⚠ BREAKING CHANGES

* give each seam module its own package root ([#201](https://github.com/yschimke/compose-ui-builder/issues/201))
* **renderer-sdk:** own package for the renderer SDK; CI builds the server against this checkout ([#183](https://github.com/yschimke/compose-ui-builder/issues/183))

### Features

* **desktop:** author against a capability catalog read from disk ([#199](https://github.com/yschimke/compose-ui-builder/issues/199)) ([1ed1c4a](https://github.com/yschimke/compose-ui-builder/commit/1ed1c4aca062165fb4bccee5f7393482d81f3084))
* **desktop:** export SVG and PNG from the desktop and IntelliJ hosts ([#173](https://github.com/yschimke/compose-ui-builder/issues/173)) ([84ba4dd](https://github.com/yschimke/compose-ui-builder/commit/84ba4dd91308980fc487165007304adb1425810a))
* **desktop:** open, save and create design files from a File menu ([#174](https://github.com/yschimke/compose-ui-builder/issues/174)) ([80087f5](https://github.com/yschimke/compose-ui-builder/commit/80087f5953e2cc12113ac1896033763c55b14729))
* **desktop:** package the desktop app for macOS and Windows ([#186](https://github.com/yschimke/compose-ui-builder/issues/186)) ([122e2bd](https://github.com/yschimke/compose-ui-builder/commit/122e2bd932aca8c267d74a5119e261d85f1b8c69))
* **intellij:** notify instead of interrupting, and a settings page for the server ([#197](https://github.com/yschimke/compose-ui-builder/issues/197)) ([5aa3709](https://github.com/yschimke/compose-ui-builder/commit/5aa37098632496a6a8be9cc6f7b546145b4c2cb8))
* **intellij:** register the plugin's actions and stop opening an editor on first show ([#184](https://github.com/yschimke/compose-ui-builder/issues/184)) ([bfd0609](https://github.com/yschimke/compose-ui-builder/commit/bfd0609b10ee749ed3e792261e4665864994362f))
* **ui-builder-desktop:** open a catalog template from the command line ([#178](https://github.com/yschimke/compose-ui-builder/issues/178)) ([0e89a0e](https://github.com/yschimke/compose-ui-builder/commit/0e89a0ea294a7bebd74c245bb44e7aec305903f6))


### Bug Fixes

* **desktop:** queue early edits, share one device-grant flow, surface headless approval ([#172](https://github.com/yschimke/compose-ui-builder/issues/172)) ([5fa430b](https://github.com/yschimke/compose-ui-builder/commit/5fa430ba919545f73732efb0efe276332ea6e03b))
* **intellij:** coalesce project-design writes instead of writing per edit ([#187](https://github.com/yschimke/compose-ui-builder/issues/187)) ([a329d0a](https://github.com/yschimke/compose-ui-builder/commit/a329d0abdbf614eaa56fe8506519d2110f18d9f9))
* **intellij:** sniff design files, close sessions with their editors, pin untilBuild ([#171](https://github.com/yschimke/compose-ui-builder/issues/171)) ([6aafa6e](https://github.com/yschimke/compose-ui-builder/commit/6aafa6ea9b1a6e7ccc2ff4916a1218c28a001405))
* **ui-builder-export:** make the exported widget body fill its frame ([#177](https://github.com/yschimke/compose-ui-builder/issues/177)) ([4f2f411](https://github.com/yschimke/compose-ui-builder/commit/4f2f4116aab732e899633142f77e358e41a76adc))
* **ui-builder:** allow Wear widget device selection ([#166](https://github.com/yschimke/compose-ui-builder/issues/166)) ([b50f16e](https://github.com/yschimke/compose-ui-builder/commit/b50f16e2baa227230088c094fdd772ccd77e8796))
* **ui-builder:** count the palette from the rows it lists ([#198](https://github.com/yschimke/compose-ui-builder/issues/198)) ([c502c9e](https://github.com/yschimke/compose-ui-builder/commit/c502c9e1651ca9650c5b37a17d0b4bf621fc2342))
* **ui-builder:** draw uncoloured text in the design's colour, not the host's ([#181](https://github.com/yschimke/compose-ui-builder/issues/181)) ([8244564](https://github.com/yschimke/compose-ui-builder/commit/824456431beb9ea20bcba24f1a3484270271e379))
* **ui-builder:** frame a Wear widget by its host container on the canvas ([#179](https://github.com/yschimke/compose-ui-builder/issues/179)) ([90f0cdd](https://github.com/yschimke/compose-ui-builder/commit/90f0cdd13dffc438f767bf38a1798426773d4485))
* **ui-builder:** hide For each from the palette, keep it in the catalog; lead the root fill ([#189](https://github.com/yschimke/compose-ui-builder/issues/189)) ([5736681](https://github.com/yschimke/compose-ui-builder/commit/5736681b992e800782a55521e42bf2257b07e590))
* **ui-builder:** keep published Remote components placeable; gate For each ([#175](https://github.com/yschimke/compose-ui-builder/issues/175)) ([38bfa7b](https://github.com/yschimke/compose-ui-builder/commit/38bfa7b9751ca82a0e34ca72f4acccde61a27c4f))
* **ui-builder:** make Wear widget authoring work end to end on desktop ([#167](https://github.com/yschimke/compose-ui-builder/issues/167)) ([5f05880](https://github.com/yschimke/compose-ui-builder/commit/5f05880d25cd1b5f41cf6a1281f1a673474b862c))


### Code Refactoring

* give each seam module its own package root ([#201](https://github.com/yschimke/compose-ui-builder/issues/201)) ([7e65a60](https://github.com/yschimke/compose-ui-builder/commit/7e65a60abc7592f6d7ea4c65e0a7414fb928e3dc)), closes [#168](https://github.com/yschimke/compose-ui-builder/issues/168)
* **renderer-sdk:** own package for the renderer SDK; CI builds the server against this checkout ([#183](https://github.com/yschimke/compose-ui-builder/issues/183)) ([228410e](https://github.com/yschimke/compose-ui-builder/commit/228410edb51f11edc51ad26cf0ca2412873b3f66)), closes [#169](https://github.com/yschimke/compose-ui-builder/issues/169)

## [3.42.0](https://github.com/yschimke/compose-ui-builder/compare/v3.41.0...v3.42.0) (2026-09-23)


### Features

* **intellij:** add design structure view ([#161](https://github.com/yschimke/compose-ui-builder/issues/161)) ([76a714c](https://github.com/yschimke/compose-ui-builder/commit/76a714c7a8edc72a45e66117970c09e6d3418a52))
* **intellij:** recognize uid design documents ([#158](https://github.com/yschimke/compose-ui-builder/issues/158)) ([1857413](https://github.com/yschimke/compose-ui-builder/commit/185741397596e20bc356583a07303891b3384219))
* **intellij:** render remote designs natively ([#163](https://github.com/yschimke/compose-ui-builder/issues/163)) ([606abbf](https://github.com/yschimke/compose-ui-builder/commit/606abbf286bda9a90385ad48a9b9849b1bccfe78))
* **intellij:** show remote design comments ([#162](https://github.com/yschimke/compose-ui-builder/issues/162)) ([05bc056](https://github.com/yschimke/compose-ui-builder/commit/05bc0562bf7e987204d4654587cbef10d0a0918a))
* **ui-builder:** use shared server folders ([#159](https://github.com/yschimke/compose-ui-builder/issues/159)) ([7ceeba2](https://github.com/yschimke/compose-ui-builder/commit/7ceeba28bcbc6cfa128f5828bb1e9dded8203d16))


### Bug Fixes

* **intellij:** open designs with supporting views ([#156](https://github.com/yschimke/compose-ui-builder/issues/156)) ([43b74cc](https://github.com/yschimke/compose-ui-builder/commit/43b74cc81c877d9cbd5cf199cd6249011292dce5))

## [3.41.0](https://github.com/yschimke/compose-ui-builder/compare/v3.40.0...v3.41.0) (2026-09-22)


### Features

* **intellij:** follow agent edits to project designs ([#152](https://github.com/yschimke/compose-ui-builder/issues/152)) ([be572a0](https://github.com/yschimke/compose-ui-builder/commit/be572a08d71eb10c85324b0fcd82ee055f3872ae))


### Bug Fixes

* **intellij:** render Material 3 previews ([#155](https://github.com/yschimke/compose-ui-builder/issues/155)) ([453c2f9](https://github.com/yschimke/compose-ui-builder/commit/453c2f9488498cec0ef977699e2b0542e7677379))

## [3.40.0](https://github.com/yschimke/compose-ui-builder/compare/v3.39.0...v3.40.0) (2026-09-22)


### Features

* **intellij:** browse remote UI Builder designs ([#149](https://github.com/yschimke/compose-ui-builder/issues/149)) ([8b36549](https://github.com/yschimke/compose-ui-builder/commit/8b36549faf8e8ad92c088aa5b768ed849514d6f9))
* **intellij:** move builder into editor window ([#145](https://github.com/yschimke/compose-ui-builder/issues/145)) ([db7f9e5](https://github.com/yschimke/compose-ui-builder/commit/db7f9e5324b1e09da7f431f768d48bc86cda41a1))
* **intellij:** open checked-in UI Builder designs ([#148](https://github.com/yschimke/compose-ui-builder/issues/148)) ([027c361](https://github.com/yschimke/compose-ui-builder/commit/027c3618e72abc6597bd800877c72a67da626141))


### Bug Fixes

* **intellij:** keep preview paired with editor ([#147](https://github.com/yschimke/compose-ui-builder/issues/147)) ([12f053a](https://github.com/yschimke/compose-ui-builder/commit/12f053a00002d126c0257b6d1085c68e81f540f0))

## [3.39.0](https://github.com/yschimke/compose-ui-builder/compare/v3.38.0...v3.39.0) (2026-09-22)


### Features

* register catalog source export adapters ([#143](https://github.com/yschimke/compose-ui-builder/issues/143)) ([3bfb2a2](https://github.com/yschimke/compose-ui-builder/commit/3bfb2a26a7dc2a378a3d9f4f783af6e58696fc32))
* **ui-builder:** add Jewel screen pickers ([#144](https://github.com/yschimke/compose-ui-builder/issues/144)) ([bdf64e9](https://github.com/yschimke/compose-ui-builder/commit/bdf64e97604749b50b4bea7a12fbe63899b44bd6))

## [3.38.0](https://github.com/yschimke/compose-ui-builder/compare/v3.37.0...v3.38.0) (2026-09-22)


### Features

* **ui-builder:** add Jewel inspector field controls ([#137](https://github.com/yschimke/compose-ui-builder/issues/137)) ([faefbed](https://github.com/yschimke/compose-ui-builder/commit/faefbed7b74372f24a8fb9f163cd679b836f2b26))
* **ui-builder:** add Jewel property inspector chrome ([#135](https://github.com/yschimke/compose-ui-builder/issues/135)) ([0c0ae31](https://github.com/yschimke/compose-ui-builder/commit/0c0ae3127bba7819417ae04d472b469786af9b42))
* **ui-builder:** add Jewel screen inspector ([#140](https://github.com/yschimke/compose-ui-builder/issues/140)) ([3391ada](https://github.com/yschimke/compose-ui-builder/commit/3391ada33ed874449c4ab0853b64d32df882f70b))
* **ui-builder:** add Jewel theme inspector ([#138](https://github.com/yschimke/compose-ui-builder/issues/138)) ([340dbbe](https://github.com/yschimke/compose-ui-builder/commit/340dbbe25fdb22845233e2ebca475822d386b7c0))


### Bug Fixes

* **intellij:** declare Jewel bridge runtime dependency ([#139](https://github.com/yschimke/compose-ui-builder/issues/139)) ([bf9e1cd](https://github.com/yschimke/compose-ui-builder/commit/bf9e1cd7ad8e6c044bbbe1569b7475412a8c50f9))

## [3.37.0](https://github.com/yschimke/compose-ui-builder/compare/v3.36.0...v3.37.0) (2026-09-22)


### Features

* **ui-builder:** add host-selectable editor chrome ([#130](https://github.com/yschimke/compose-ui-builder/issues/130)) ([21a7fcd](https://github.com/yschimke/compose-ui-builder/commit/21a7fcd5787cc4ae8e4aac26580d3089b1e5e031))
* **ui-builder:** add Jewel component browser chrome ([#133](https://github.com/yschimke/compose-ui-builder/issues/133)) ([b05aa97](https://github.com/yschimke/compose-ui-builder/commit/b05aa97dd261ef62781a30b18e32006945c99365))
* **ui-builder:** add Jewel editor toolbars ([#134](https://github.com/yschimke/compose-ui-builder/issues/134)) ([1d61ec0](https://github.com/yschimke/compose-ui-builder/commit/1d61ec08265cb110ec64c1898c3389f10dce52c8))
* **ui-builder:** render editor menus with host chrome ([#132](https://github.com/yschimke/compose-ui-builder/issues/132)) ([092f5a4](https://github.com/yschimke/compose-ui-builder/commit/092f5a4062733d307678365171de60eb2f789aa2))

## [3.36.0](https://github.com/yschimke/compose-ui-builder/compare/v3.35.0...v3.36.0) (2026-09-21)


### Features

* **ui-builder:** add IntelliJ plugin POC ([#124](https://github.com/yschimke/compose-ui-builder/issues/124)) ([8c732d0](https://github.com/yschimke/compose-ui-builder/commit/8c732d0fd350927d4c188fff44bdf8265a31f2dd))


### Bug Fixes

* retain canvas for legacy runtime pins ([#129](https://github.com/yschimke/compose-ui-builder/issues/129)) ([5eb14ac](https://github.com/yschimke/compose-ui-builder/commit/5eb14ac76430c15a7f0d6bbaad6f63b36728a968))

## [3.35.0](https://github.com/yschimke/compose-ui-builder/compare/v3.34.0...v3.35.0) (2026-09-21)


### Features

* pin catalogs to delivered runtimes ([#125](https://github.com/yschimke/compose-ui-builder/issues/125)) ([4b28f70](https://github.com/yschimke/compose-ui-builder/commit/4b28f7060c0a51f414e0502bb94403c71aa222e7))


### Bug Fixes

* preserve catalog executor constructor ABI ([#127](https://github.com/yschimke/compose-ui-builder/issues/127)) ([d372cc9](https://github.com/yschimke/compose-ui-builder/commit/d372cc962c4bbc6c1562c79853690e402f949895))

## [3.34.0](https://github.com/yschimke/compose-ui-builder/compare/v3.33.0...v3.34.0) (2026-09-21)


### Features

* mount pinned catalog runtimes in the editor ([#121](https://github.com/yschimke/compose-ui-builder/issues/121)) ([1faa1e3](https://github.com/yschimke/compose-ui-builder/commit/1faa1e39c6798c69537fcace49d78fdb3c3bf665))


### Bug Fixes

* settle actionable runtime geometry ([#123](https://github.com/yschimke/compose-ui-builder/issues/123)) ([76c7964](https://github.com/yschimke/compose-ui-builder/commit/76c7964895859340ecf79b750faf2164e037e4a1))

## [3.33.0](https://github.com/yschimke/compose-ui-builder/compare/v3.32.0...v3.33.0) (2026-09-21)


### Features

* organize designs into folders ([#108](https://github.com/yschimke/compose-ui-builder/issues/108)) ([bbe3412](https://github.com/yschimke/compose-ui-builder/commit/bbe3412692aa27cf48e2da06785d4a3f3992828a))
* **renderer-sdk:** apply canvas modifiers ([#116](https://github.com/yschimke/compose-ui-builder/issues/116)) ([4d5b7f1](https://github.com/yschimke/compose-ui-builder/commit/4d5b7f1367f75a17fbcdd00a99b5213fcb455c96))
* **renderer-sdk:** expose indexed slot items ([#114](https://github.com/yschimke/compose-ui-builder/issues/114)) ([598536e](https://github.com/yschimke/compose-ui-builder/commit/598536ecbf9eecb60597fcde7ec3bfb8cefd7bb9))
* **renderer-sdk:** expose material icon vectors ([#117](https://github.com/yschimke/compose-ui-builder/issues/117)) ([1aca136](https://github.com/yschimke/compose-ui-builder/commit/1aca136b7bab288662bbed0364ee0e9f76213cad))
* **renderer-sdk:** host canvas documents ([#113](https://github.com/yschimke/compose-ui-builder/issues/113)) ([0dfba04](https://github.com/yschimke/compose-ui-builder/commit/0dfba047c37a82fe30056d425667db00dda4c3bf))
* **renderer-sdk:** own canvas tree traversal ([#110](https://github.com/yschimke/compose-ui-builder/issues/110)) ([49916f9](https://github.com/yschimke/compose-ui-builder/commit/49916f97ba57496727cdc0f59eceef8c5ed905da))
* **renderer-sdk:** prepare canvas nodes ([#111](https://github.com/yschimke/compose-ui-builder/issues/111)) ([aeffdf0](https://github.com/yschimke/compose-ui-builder/commit/aeffdf02b7ac48b24bd63e9cf1d8abbf6db8d476))
* **renderer-sdk:** render catalog registries ([#115](https://github.com/yschimke/compose-ui-builder/issues/115)) ([ae04eec](https://github.com/yschimke/compose-ui-builder/commit/ae04eecf62c8563429c8ef5d0307a1d7bba3b2a0))
* **renderer-sdk:** render structural nodes ([#112](https://github.com/yschimke/compose-ui-builder/issues/112)) ([59aa310](https://github.com/yschimke/compose-ui-builder/commit/59aa310ef483d9acae5451a4a2a375053b820657))
* **renderer-sdk:** update bound adapter state ([#119](https://github.com/yschimke/compose-ui-builder/issues/119)) ([4ec9153](https://github.com/yschimke/compose-ui-builder/commit/4ec915397e1704cac8df9f7301a3d03237bef83b))


### Bug Fixes

* **renderer-sdk:** retain document template metadata ([#118](https://github.com/yschimke/compose-ui-builder/issues/118)) ([8e0763b](https://github.com/yschimke/compose-ui-builder/commit/8e0763b23975461697e332e74de085f80847a1ef))
* **ui-builder:** recover designs from stale catalog pins ([#120](https://github.com/yschimke/compose-ui-builder/issues/120)) ([55e1234](https://github.com/yschimke/compose-ui-builder/commit/55e1234cae86d932e58dab34fdc2615051cd8cba))

## [3.32.0](https://github.com/yschimke/compose-ui-builder/compare/v3.31.0...v3.32.0) (2026-09-20)


### Features

* **renderer-sdk:** add catalog adapter registry ([#102](https://github.com/yschimke/compose-ui-builder/issues/102)) ([e0da0f0](https://github.com/yschimke/compose-ui-builder/commit/e0da0f0014e208c0e44a4eb93f8ec55722d31876))
* **renderer:** consume explicit protocol v2 surfaces ([#104](https://github.com/yschimke/compose-ui-builder/issues/104)) ([71ab472](https://github.com/yschimke/compose-ui-builder/commit/71ab4726c936497f7c42015ec80ea23a969dec59))


### Bug Fixes

* **build:** consume renderer protocol v2 contracts ([#105](https://github.com/yschimke/compose-ui-builder/issues/105)) ([21b22ef](https://github.com/yschimke/compose-ui-builder/commit/21b22ef66cb50bf906e43257de8801f917adf451))
* **ui-builder:** rank exact catalog search results ([#107](https://github.com/yschimke/compose-ui-builder/issues/107)) ([cad193d](https://github.com/yschimke/compose-ui-builder/commit/cad193d3ebb597d9182aa7a534d47ea266f1ed6f))

## [3.31.0](https://github.com/yschimke/compose-ui-builder/compare/v3.30.0...v3.31.0) (2026-09-20)


### Features

* **ui-builder:** play catalog document previews in browser ([#100](https://github.com/yschimke/compose-ui-builder/issues/100)) ([d105d7f](https://github.com/yschimke/compose-ui-builder/commit/d105d7fd1f52538eb46b7de9495d8394e8cdf1b4))


### Bug Fixes

* **ui-builder:** align Jetcaster oracle with real components ([#99](https://github.com/yschimke/compose-ui-builder/issues/99)) ([4ddc6f6](https://github.com/yschimke/compose-ui-builder/commit/4ddc6f64f567ae085474befe55d71ec4ec7a23cb))

## [3.30.0](https://github.com/yschimke/compose-ui-builder/compare/v3.29.0...v3.30.0) (2026-09-20)


### Features

* **ui-builder:** add OpenCode MCP prompt ([#95](https://github.com/yschimke/compose-ui-builder/issues/95)) ([991802f](https://github.com/yschimke/compose-ui-builder/commit/991802f48318048b5c8f6715406358d0f68a00fc))
* **ui-builder:** draw the round screen frame by adapter, not by component id ([#79](https://github.com/yschimke/compose-ui-builder/issues/79)) ([0853936](https://github.com/yschimke/compose-ui-builder/commit/08539364fcbd5b964e32fc9f6ef19fceb2cebaa6))
* **ui-builder:** make issues actionable ([#83](https://github.com/yschimke/compose-ui-builder/issues/83)) ([073f913](https://github.com/yschimke/compose-ui-builder/commit/073f91340043fd0bcb8d960bb455272d3641179c))


### Bug Fixes

* group compatible BOM updates ([#98](https://github.com/yschimke/compose-ui-builder/issues/98)) ([8fa95ac](https://github.com/yschimke/compose-ui-builder/commit/8fa95ac3df624c80298ad7ef56749a6ff0b54b83))
* **remote-m3:** remove borrowed Material surface ([#81](https://github.com/yschimke/compose-ui-builder/issues/81)) ([89429d3](https://github.com/yschimke/compose-ui-builder/commit/89429d3eabd5a091065fafccc87b7d6788d32b07))
* **ui-builder:** address review regressions ([#89](https://github.com/yschimke/compose-ui-builder/issues/89)) ([19ffb7f](https://github.com/yschimke/compose-ui-builder/commit/19ffb7fb282ad30be9e5aa4c60323db11bea371f))
* **ui-builder:** crop palette thumbnails to component bounds ([#94](https://github.com/yschimke/compose-ui-builder/issues/94)) ([20eddcf](https://github.com/yschimke/compose-ui-builder/commit/20eddcf763c0b2c3e74a24f90b547d94b18ba76f))
* **ui-builder:** honor explicit frame adapters for themes ([#86](https://github.com/yschimke/compose-ui-builder/issues/86)) ([674ca38](https://github.com/yschimke/compose-ui-builder/commit/674ca38a33495b6dd8366be2615ae1584cd3df64))
* **ui-builder:** keep Wear list edges safe in every preview lane ([#88](https://github.com/yschimke/compose-ui-builder/issues/88)) ([41531dc](https://github.com/yschimke/compose-ui-builder/commit/41531dc1d6d0be3ba8143a832b66f27af497c8ac))
* **ui-builder:** load adapters for direct catalog renders ([#91](https://github.com/yschimke/compose-ui-builder/issues/91)) ([55b900f](https://github.com/yschimke/compose-ui-builder/commit/55b900f77eb28f1f51e39e9b93cea8602e4d4ea7))
* **ui-builder:** protect inspector drafts ([#82](https://github.com/yschimke/compose-ui-builder/issues/82)) ([8a6062d](https://github.com/yschimke/compose-ui-builder/commit/8a6062d54c937875a854afeedbeb706a20e93ade))
* **ui-builder:** read a pane's locals in the editor, not inside its scene ([#80](https://github.com/yschimke/compose-ui-builder/issues/80)) ([45d311b](https://github.com/yschimke/compose-ui-builder/commit/45d311b881d6a0711f9bf9b5d1dce4ba38adb1c1))
* **ui-builder:** use real preview components ([#84](https://github.com/yschimke/compose-ui-builder/issues/84)) ([3215f91](https://github.com/yschimke/compose-ui-builder/commit/3215f9148a7617f7f7c0a5cb335b153cd0633f69))

## [3.29.0](https://github.com/yschimke/compose-ui-builder/compare/v3.28.0...v3.29.0) (2026-09-19)


### Features

* adopt the builder-backed catalog capability from contracts 3.2.0 ([#43](https://github.com/yschimke/compose-ui-builder/issues/43)) ([dcddb6d](https://github.com/yschimke/compose-ui-builder/commit/dcddb6d9f0e4aa695bc43300830a2e3c036f5553))
* **release:** publish desktop app package ([#53](https://github.com/yschimke/compose-ui-builder/issues/53)) ([6ad5a71](https://github.com/yschimke/compose-ui-builder/commit/6ad5a71e6f30c1a87ced3613dad4d55acea62579))
* **ui-builder:** add offline desktop host ([#51](https://github.com/yschimke/compose-ui-builder/issues/51)) ([53feab8](https://github.com/yschimke/compose-ui-builder/commit/53feab806772287542c1b1cebdc77950fc6dfe3e))
* **ui-builder:** draw device panes in their own scene, and turn the wheel into the side button ([#52](https://github.com/yschimke/compose-ui-builder/issues/52)) ([06f43a0](https://github.com/yschimke/compose-ui-builder/commit/06f43a0851bd90361082f1fb8a1cec42848824bd))
* **ui-builder:** gate the Wear device configuration on the declared platform ([#65](https://github.com/yschimke/compose-ui-builder/issues/65)) ([bcc16d6](https://github.com/yschimke/compose-ui-builder/commit/bcc16d6eb83bc5ce12187fb0d723f267b86a243e))
* **ui-builder:** one frame view per pane, and a grid for small ones ([#44](https://github.com/yschimke/compose-ui-builder/issues/44)) ([54e8d1b](https://github.com/yschimke/compose-ui-builder/commit/54e8d1b545eb6c8a267a6b39099568e6eac95f71))
* **ui-builder:** read a catalog's frame from the catalog ([#58](https://github.com/yschimke/compose-ui-builder/issues/58)) ([b7dbfed](https://github.com/yschimke/compose-ui-builder/commit/b7dbfeddd4dd5a4783a0137ea938402f7a12e58b))
* **ui-builder:** render desktop previews remotely ([#56](https://github.com/yschimke/compose-ui-builder/issues/56)) ([57a188b](https://github.com/yschimke/compose-ui-builder/commit/57a188b5f8ae00bc9677b975973e4eea8d5326ff))
* **ui-builder:** say why the editor is blank when WebGL is unavailable ([#35](https://github.com/yschimke/compose-ui-builder/issues/35)) ([d89d433](https://github.com/yschimke/compose-ui-builder/commit/d89d433320155188c169188bce095bb593e5a554))


### Bug Fixes

* harden UI Builder validation and guidance ([#73](https://github.com/yschimke/compose-ui-builder/issues/73)) ([b257b88](https://github.com/yschimke/compose-ui-builder/commit/b257b88739f6fe15684187689383c64b8a4c5575))
* preview selected devices only ([#60](https://github.com/yschimke/compose-ui-builder/issues/60)) ([7286adf](https://github.com/yschimke/compose-ui-builder/commit/7286adf22458aa7e3418d7f4db9e1032bd49c9d7))
* preview Wear widget hosts by default ([#55](https://github.com/yschimke/compose-ui-builder/issues/55)) ([cab1fd4](https://github.com/yschimke/compose-ui-builder/commit/cab1fd43d4fc6785f4defdb81d8a301799a7c02f))
* **release:** a publish_tag input, so a stalled release can be re-run for its tag ([#41](https://github.com/yschimke/compose-ui-builder/issues/41)) ([0dfb8f2](https://github.com/yschimke/compose-ui-builder/commit/0dfb8f28cefbf565192be0c290806e12a5e1613b))
* **ui-builder:** connect the Wear clock and scroll indicator to the list ([#70](https://github.com/yschimke/compose-ui-builder/issues/70)) ([462d244](https://github.com/yschimke/compose-ui-builder/commit/462d244d209a2cde9a577ff19cdc8d61df4fd3e3))
* **ui-builder:** declare every Wear component on its own terms ([#50](https://github.com/yschimke/compose-ui-builder/issues/50)) ([fb6cec2](https://github.com/yschimke/compose-ui-builder/commit/fb6cec2184590b92cf9c48390d63fee005817828))
* **ui-builder:** derive the preview fan-out from tagNodes ([#46](https://github.com/yschimke/compose-ui-builder/issues/46)) ([7a0b465](https://github.com/yschimke/compose-ui-builder/commit/7a0b4651980a060a8d809e18ade68d857d38d038))
* **ui-builder:** draw Wear text with Wear's own Text, and read what it declares ([#49](https://github.com/yschimke/compose-ui-builder/issues/49)) ([968f79f](https://github.com/yschimke/compose-ui-builder/commit/968f79f45f5ed759007db49e905d8e26737a9199))
* **ui-builder:** generate the page nonce without a secure context ([#42](https://github.com/yschimke/compose-ui-builder/issues/42)) ([d56a0f5](https://github.com/yschimke/compose-ui-builder/commit/d56a0f5ce093bdfc8068ccd063604db54942fdaa))
* **ui-builder:** hit-test against the box a node is drawn at, not the part the viewport shows ([#36](https://github.com/yschimke/compose-ui-builder/issues/36)) ([d2ab8fd](https://github.com/yschimke/compose-ui-builder/commit/d2ab8fd9c617476b6c6961697a22e381a051262a))
* **ui-builder:** lay Wear components out against the document's watch ([#47](https://github.com/yschimke/compose-ui-builder/issues/47)) ([6855175](https://github.com/yschimke/compose-ui-builder/commit/6855175a86cfa19a4c4bc67fe66bed8e02260d90))
* **ui-builder:** open properties on selection ([#54](https://github.com/yschimke/compose-ui-builder/issues/54)) ([33b7de2](https://github.com/yschimke/compose-ui-builder/commit/33b7de24b5eb8633f0e2bc8381255f17a57f205c))
* **ui-builder:** open properties on selection ([#57](https://github.com/yschimke/compose-ui-builder/issues/57)) ([61af1fc](https://github.com/yschimke/compose-ui-builder/commit/61af1fcf7cd7cfc5db95e6603d1b595220230ac2))
* **ui-builder:** retire the Wear lookalike's stale claims and dead numbers ([#48](https://github.com/yschimke/compose-ui-builder/issues/48)) ([f0c6468](https://github.com/yschimke/compose-ui-builder/commit/f0c646816fb66d037159c594d2dacb05b38fe679))
* **ui-builder:** two tests that were pinning behaviour that has moved ([#72](https://github.com/yschimke/compose-ui-builder/issues/72)) ([92bdad3](https://github.com/yschimke/compose-ui-builder/commit/92bdad3b31d908599b14c666856943561d7b1b76))

## [3.28.0](https://github.com/yschimke/compose-ui-builder/compare/v3.27.0...v3.28.0) (2026-09-19)


### Features

* **ui-builder:** a canvas move is a hold, not a hurried drag ([#29](https://github.com/yschimke/compose-ui-builder/issues/29)) ([11a00b3](https://github.com/yschimke/compose-ui-builder/commit/11a00b318be1752522f693344ebcba4e786d0b8e))
* **ui-builder:** a drag at the workspace edge scrolls the design under it ([#19](https://github.com/yschimke/compose-ui-builder/issues/19)) ([4ff8cfb](https://github.com/yschimke/compose-ui-builder/commit/4ff8cfb961af5b89750be680ff6147670febca62))
* **ui-builder:** a palette drop on the empty ground adds beside the design ([#20](https://github.com/yschimke/compose-ui-builder/issues/20)) ([75dcc84](https://github.com/yschimke/compose-ui-builder/commit/75dcc84130bde6c1fb12d8c84e3eeccc5b2301a5))
* **ui-builder:** a slot is hit anywhere in the container it fills ([#24](https://github.com/yschimke/compose-ui-builder/issues/24)) ([918346a](https://github.com/yschimke/compose-ui-builder/commit/918346a215c140ae72faa02e1d05b352e44bdca7))
* **ui-builder:** a tidy command that snaps authored dp values to the 4dp grid ([#27](https://github.com/yschimke/compose-ui-builder/issues/27)) ([13d1635](https://github.com/yschimke/compose-ui-builder/commit/13d1635cd899fdb83a875fda06e39ac05e5011db))
* **ui-builder:** an empty recommended slot invites a drop, and stops inviting once filled ([#33](https://github.com/yschimke/compose-ui-builder/issues/33)) ([7cf5ecb](https://github.com/yschimke/compose-ui-builder/commit/7cf5ecbbcc3b8502bfed930ab77d2a038e70cbda))
* **ui-builder:** land drags at the seam they show, and breadcrumb the selection ([#16](https://github.com/yschimke/compose-ui-builder/issues/16)) ([21b4340](https://github.com/yschimke/compose-ui-builder/commit/21b4340a9becd9412fc6d9eda6931acefe7bd138))
* **ui-builder:** make a corrupted design its owner's to list, rename and delete ([#25](https://github.com/yschimke/compose-ui-builder/issues/25)) ([cfab73a](https://github.com/yschimke/compose-ui-builder/commit/cfab73a4f82b0ff3ca7dd2a96693b0f492ff5bbe))
* **ui-builder:** pin components to the top of the palette, and let each catalog say which ([#31](https://github.com/yschimke/compose-ui-builder/issues/31)) ([8bad0d9](https://github.com/yschimke/compose-ui-builder/commit/8bad0d90c8d57779bd8a407c812c0cae9bb21aca))
* **ui-builder:** the canvas move marks where the carried node came from ([#21](https://github.com/yschimke/compose-ui-builder/issues/21)) ([3bfbe66](https://github.com/yschimke/compose-ui-builder/commit/3bfbe6655e1eaf1fc6dba366b63658fd172558cf))


### Bug Fixes

* **ui-builder:** draw the unrolled Wear list as a column, not a lazy layout ([#34](https://github.com/yschimke/compose-ui-builder/issues/34)) ([450833b](https://github.com/yschimke/compose-ui-builder/commit/450833b8cb69bc01998672bd059f20730403e4b1))
* **ui-builder:** import the box an empty widget host frame is filled with ([#30](https://github.com/yschimke/compose-ui-builder/issues/30)) ([0f1f10b](https://github.com/yschimke/compose-ui-builder/commit/0f1f10b4bdc4b13b4e8ec6f95aad3a54ba6878b4))
* **ui-builder:** let a design's owner delete it while it is unusable ([#17](https://github.com/yschimke/compose-ui-builder/issues/17)) ([128c686](https://github.com/yschimke/compose-ui-builder/commit/128c6863caf27a03f9dc61af22a29261cbadc7fc))
* **ui-builder:** the drag ghost holds still, and the marker reads at any zoom ([#32](https://github.com/yschimke/compose-ui-builder/issues/32)) ([227a7ef](https://github.com/yschimke/compose-ui-builder/commit/227a7ef3ff936534cf54e4820d16caef5fcdc458))

## [3.27.0](https://github.com/yschimke/compose-ui-builder/compare/v3.26.0...v3.27.0) (2026-09-17)


### Bug Fixes

* **release:** order the publish-set checks ahead of the Central upload ([#14](https://github.com/yschimke/compose-ui-builder/issues/14)) ([cd31a3e](https://github.com/yschimke/compose-ui-builder/commit/cd31a3e12e8e83dc43472bd40a59c8b121b6068c))

## [3.26.0](https://github.com/yschimke/compose-ui-builder/compare/v3.25.0...v3.26.0) (2026-09-17)


### ⚠ BREAKING CHANGES

* **ui-builder:** borrow only foundation into the wear catalog ([#389](https://github.com/yschimke/compose-ui-builder/issues/389))
* **render-host:** consume the render host from compose-ai-tools ([#289](https://github.com/yschimke/compose-ui-builder/issues/289))

### Features

* admin screen to manage UI-builder designs, and a friendlier New design dialog ([#423](https://github.com/yschimke/compose-ui-builder/issues/423)) ([4e10565](https://github.com/yschimke/compose-ui-builder/commit/4e1056593fa0e12dd51c8b67304ec40efb11fe2d))
* complete preview server release and deployment handoff ([#4](https://github.com/yschimke/compose-ui-builder/issues/4)) ([6c0f3b4](https://github.com/yschimke/compose-ui-builder/commit/6c0f3b43c81338163df324836d9e9907a631907c))
* **deploy:** serve the Wear M3 authoring adapter by default ([#373](https://github.com/yschimke/compose-ui-builder/issues/373)) ([e87ea87](https://github.com/yschimke/compose-ui-builder/commit/e87ea8707af47a06a9c7b6e6de083811299a956b))
* **deps:** take contracts 3.0.0 and move to its builders ([#910](https://github.com/yschimke/compose-ui-builder/issues/910)) ([d74e3e2](https://github.com/yschimke/compose-ui-builder/commit/d74e3e2701bfab9207ae8a4d8c2d6a9603d7772f))
* **deps:** take every upstream line by its BOM, and move to the daemon's builders ([#915](https://github.com/yschimke/compose-ui-builder/issues/915)) ([4f37e4c](https://github.com/yschimke/compose-ui-builder/commit/4f37e4cd7987ae376cf9de7782415c9ab72030c4))
* **design-pages:** composite shared plates beneath the sheet ([#894](https://github.com/yschimke/compose-ui-builder/issues/894)) ([844216b](https://github.com/yschimke/compose-ui-builder/commit/844216bc922946d9561840ceb7bb54a93297859b))
* **design:** compile and render a design locally with --local ([#561](https://github.com/yschimke/compose-ui-builder/issues/561)) ([f7b5877](https://github.com/yschimke/compose-ui-builder/commit/f7b58776536665f2675b7ab8206585f8d3dbdb72))
* establish standalone preview server repository ([2aaea7e](https://github.com/yschimke/compose-ui-builder/commit/2aaea7e78589f2006c98f9e013a8553c66f9bb6b))
* establish standalone preview server repository ([a315715](https://github.com/yschimke/compose-ui-builder/commit/a3157151ada40abb189518c523950d2eac0809cc))
* extract the render host and preview history into :render-host ([#38](https://github.com/yschimke/compose-ui-builder/issues/38)) ([71a2482](https://github.com/yschimke/compose-ui-builder/commit/71a24825ca5ec096e0b460e4b29f972e18986956))
* fold identical sibling runs into one repeat in the Compose export ([#647](https://github.com/yschimke/compose-ui-builder/issues/647)) ([cf123dd](https://github.com/yschimke/compose-ui-builder/commit/cf123ddd815aa89a9a5c4cb4039f876a2b4ad039))
* **mcp:** move the MCP server into this repository ([#308](https://github.com/yschimke/compose-ui-builder/issues/308)) ([b1b0d6e](https://github.com/yschimke/compose-ui-builder/commit/b1b0d6e4200c683d499c7bf1e1247e98222419b5))
* read the shelf role, lanes, call and slot order a builtin now states ([#886](https://github.com/yschimke/compose-ui-builder/issues/886)) ([9448263](https://github.com/yschimke/compose-ui-builder/commit/9448263809febfa8c93c80980513d1725f10d7e5))
* **render-host:** consume the render host from compose-ai-tools ([#289](https://github.com/yschimke/compose-ui-builder/issues/289)) ([f631a16](https://github.com/yschimke/compose-ui-builder/commit/f631a16667071c972cbe1241a43dea2780473756))
* **serve:** add authenticated catalog MCP ([#179](https://github.com/yschimke/compose-ui-builder/issues/179)) ([b763205](https://github.com/yschimke/compose-ui-builder/commit/b7632053c853f307707ef30bd39d44bb7d70af3c))
* **serve:** add native Wasm catalog and UI composer ([941d46e](https://github.com/yschimke/compose-ui-builder/commit/941d46ea63033231467a1a3b04d3a3d1d9feda85))
* **serve:** drive a local Gradle build through a build-host process ([#294](https://github.com/yschimke/compose-ui-builder/issues/294)) ([12850a4](https://github.com/yschimke/compose-ui-builder/commit/12850a4ccec34e840094e2c35e115df945d2c231))
* **serve:** generate the Compose export from the discovered component record ([#236](https://github.com/yschimke/compose-ui-builder/issues/236)) ([a98d3cf](https://github.com/yschimke/compose-ui-builder/commit/a98d3cf56cb5660b6826275214b1e8d8a1356abd))
* **serve:** migrate stored icon keys to Material Symbols names ([#741](https://github.com/yschimke/compose-ui-builder/issues/741)) ([1fa3c18](https://github.com/yschimke/compose-ui-builder/commit/1fa3c18553a45bbe60def6568831ab455dda7e92))
* **serve:** open the UI builder locally in one command ([#590](https://github.com/yschimke/compose-ui-builder/issues/590)) ([2810edd](https://github.com/yschimke/compose-ui-builder/commit/2810edd2f355a44da2f94a94267219f859691c83))
* **serve:** read the components a project shares between its designs ([#685](https://github.com/yschimke/compose-ui-builder/issues/685)) ([45e2085](https://github.com/yschimke/compose-ui-builder/commit/45e20857331e88f83ba2a132d4c69fbd90ea32d9))
* **serve:** report when a design's shared components have moved ([#702](https://github.com/yschimke/compose-ui-builder/issues/702)) ([0ab6eb0](https://github.com/yschimke/compose-ui-builder/commit/0ab6eb0e0170bcdaf979d129c761139fe0c52a8c))
* **serve:** resolve Material Symbols outlines from the pinned variable font ([#738](https://github.com/yschimke/compose-ui-builder/issues/738)) ([1580687](https://github.com/yschimke/compose-ui-builder/commit/1580687a2743bf96e5ec5c7969e2c300b5fcf80d))
* **serve:** serve a captured Remote Compose document as JSON ([#698](https://github.com/yschimke/compose-ui-builder/issues/698)) ([bc7b61f](https://github.com/yschimke/compose-ui-builder/commit/bc7b61fdbab58d8017b995e0f7f96b5264aababf))
* stop publishing to Maven Central, and ship only the release archives (breaking) ([#794](https://github.com/yschimke/compose-ui-builder/issues/794)) ([1b8bb6b](https://github.com/yschimke/compose-ui-builder/commit/1b8bb6bb550cb6cbecc9ef6a88b22d5b5e023ea7))
* sweep recent preview server changes ([04960dc](https://github.com/yschimke/compose-ui-builder/commit/04960dccee05d2a1e2c502da0bd34e57b08e0863))
* **ui-builder-runtime:** apply a modifier chain the browser writes ([#382](https://github.com/yschimke/compose-ui-builder/issues/382)) ([c18203e](https://github.com/yschimke/compose-ui-builder/commit/c18203e46952811a10411ced9cf006ae2dd2fb1d))
* **ui-builder-runtime:** enable explicitApi() and check an ABI dump ([#307](https://github.com/yschimke/compose-ui-builder/issues/307)) ([2d7636e](https://github.com/yschimke/compose-ui-builder/commit/2d7636ec9dc4dea2c518c7d16014556901646b1b))
* **ui-builder:** a board node for several items, and variant panes beside the design ([#600](https://github.com/yschimke/compose-ui-builder/issues/600)) ([7fa630a](https://github.com/yschimke/compose-ui-builder/commit/7fa630a2b147244cd9ea784c1562b8b636a0cf17))
* **ui-builder:** a design command, so getting pixels or source out is not a bespoke script ([#532](https://github.com/yschimke/compose-ui-builder/issues/532)) ([4711319](https://github.com/yschimke/compose-ui-builder/commit/4711319af6ef50d95430b1826d9cbd9022a2904d))
* **ui-builder:** a Remote design's actions and placements, and five findings from [#686](https://github.com/yschimke/compose-ui-builder/issues/686)'s review ([#690](https://github.com/yschimke/compose-ui-builder/issues/690)) ([485ce7e](https://github.com/yschimke/compose-ui-builder/commit/485ce7ed8012f13a9b4ecae792a27e4fc0933138))
* **ui-builder:** a RemoveNodeProperty operation, sent as the null write the server unsets on ([#505](https://github.com/yschimke/compose-ui-builder/issues/505)) ([adc77b4](https://github.com/yschimke/compose-ui-builder/commit/adc77b42b6ecaa0008c2eab9bc5dcc6f360597df))
* **ui-builder:** a tool surface an agent can work in — context, correction, cleanup ([#495](https://github.com/yschimke/compose-ui-builder/issues/495)) ([90d5736](https://github.com/yschimke/compose-ui-builder/commit/90d5736a5fb93d0ad0d1fee2cf53bc1ac5627bfe))
* **ui-builder:** a Wear sample, and the two things building it found ([#908](https://github.com/yschimke/compose-ui-builder/issues/908)) ([0140d5e](https://github.com/yschimke/compose-ui-builder/commit/0140d5e22312c1b39555ce58d9d8c68228cd039a))
* **ui-builder:** add `layout/flow-row`, a row that wraps ([#923](https://github.com/yschimke/compose-ui-builder/issues/923)) ([561da15](https://github.com/yschimke/compose-ui-builder/commit/561da154bc270a6e1c6b8fce6eaadd80c75fce68))
* **ui-builder:** add a dialog and date and time pickers to the catalog ([#375](https://github.com/yschimke/compose-ui-builder/issues/375)) ([8c5f34b](https://github.com/yschimke/compose-ui-builder/commit/8c5f34b29ce88d90d503957eff1eed1e0de37807))
* **ui-builder:** add a Lottie element to the Wear widget catalog ([#457](https://github.com/yschimke/compose-ui-builder/issues/457)) ([a1aa7ae](https://github.com/yschimke/compose-ui-builder/commit/a1aa7ae2e2422b764a5fd29605275f9f63a7b0ca))
* **ui-builder:** add a slider and a progress indicator to the catalog ([#386](https://github.com/yschimke/compose-ui-builder/issues/386)) ([c023e24](https://github.com/yschimke/compose-ui-builder/commit/c023e240b06f8e85bda0a5edddbcfbb1c4b41fd2))
* **ui-builder:** add a text field and a radio button to the catalog ([#384](https://github.com/yschimke/compose-ui-builder/issues/384)) ([af49f92](https://github.com/yschimke/compose-ui-builder/commit/af49f92d3dc8e71ac142aa49565d213a62d4bbef))
* **ui-builder:** add catalog-scoped instances and Wear scaffolds ([#164](https://github.com/yschimke/compose-ui-builder/issues/164)) ([75137a7](https://github.com/yschimke/compose-ui-builder/commit/75137a7fffff15d1940efcc4a311befa14542a66))
* **ui-builder:** add checkbox and switch to the catalog ([#383](https://github.com/yschimke/compose-ui-builder/issues/383)) ([5ca8d7a](https://github.com/yschimke/compose-ui-builder/commit/5ca8d7ab6c9cf4906dcad98fb3fde76b66c8b4e1))
* **ui-builder:** add editor operation controls ([#100](https://github.com/yschimke/compose-ui-builder/issues/100)) ([1cf2149](https://github.com/yschimke/compose-ui-builder/commit/1cf2149b24fc9ffde03e453ec4887c7cd496d843))
* **ui-builder:** add Google icon property editing ([#153](https://github.com/yschimke/compose-ui-builder/issues/153)) ([0b80974](https://github.com/yschimke/compose-ui-builder/commit/0b809743786655816506a9b30ae4abb77f916eb4))
* **ui-builder:** add interactive Wasm editor ([#96](https://github.com/yschimke/compose-ui-builder/issues/96)) ([7341ec3](https://github.com/yschimke/compose-ui-builder/commit/7341ec3ed52916113808158da3a7cdaa6767922c))
* **ui-builder:** add native Confetti render slice ([#80](https://github.com/yschimke/compose-ui-builder/issues/80)) ([a0b8cb0](https://github.com/yschimke/compose-ui-builder/commit/a0b8cb032e1c0dc0c7caa2d6442c7128041539a2))
* **ui-builder:** add protocol client adapter ([#107](https://github.com/yschimke/compose-ui-builder/issues/107)) ([c30d138](https://github.com/yschimke/compose-ui-builder/commit/c30d138396e1498012c4f57fbeedd32e2b0ec165))
* **ui-builder:** add protocol service foundation ([#101](https://github.com/yschimke/compose-ui-builder/issues/101)) ([d32d436](https://github.com/yschimke/compose-ui-builder/commit/d32d436955c73844f37417b48c877e2503eed158))
* **ui-builder:** add recoverable persistence migration ([0576609](https://github.com/yschimke/compose-ui-builder/commit/0576609e91ef8eced968eae02fd6b9e918126573))
* **ui-builder:** add sandboxed renderer runtime ([5d2acbc](https://github.com/yschimke/compose-ui-builder/commit/5d2acbcfe8e08540be8bdf9cb939a78f6e0ba5c4))
* **ui-builder:** add screen environment controls ([#152](https://github.com/yschimke/compose-ui-builder/issues/152)) ([0f5ec19](https://github.com/yschimke/compose-ui-builder/commit/0f5ec1961b36776271a2bc53ef85b0f2610b6d93))
* **ui-builder:** add top-level theme builder ([#160](https://github.com/yschimke/compose-ui-builder/issues/160)) ([48884b8](https://github.com/yschimke/compose-ui-builder/commit/48884b8d911ed219b14e714105034a534bf7b586))
* **ui-builder:** add typed authoring inspector ([#150](https://github.com/yschimke/compose-ui-builder/issues/150)) ([78ae2b6](https://github.com/yschimke/compose-ui-builder/commit/78ae2b62443ea73f10e9ccebcd735d22ee921c1a))
* **ui-builder:** add wave one foundations ([#86](https://github.com/yschimke/compose-ui-builder/issues/86)) ([a8aa637](https://github.com/yschimke/compose-ui-builder/commit/a8aa637292d89c58c068a5501c0fbd025eac47b8))
* **ui-builder:** add website design creation flow ([#167](https://github.com/yschimke/compose-ui-builder/issues/167)) ([5f71987](https://github.com/yschimke/compose-ui-builder/commit/5f719873d6325080f3824db9e6fdb9266bd1f95b))
* **ui-builder:** adopt contracts 2.3.0 ([#146](https://github.com/yschimke/compose-ui-builder/issues/146)) ([7a8ca87](https://github.com/yschimke/compose-ui-builder/commit/7a8ca870724ed498cd7b2e4279ca4a3ef0f6e9d9))
* **ui-builder:** adopt Jetcaster benchmark ([#82](https://github.com/yschimke/compose-ui-builder/issues/82)) ([4ae91dd](https://github.com/yschimke/compose-ui-builder/commit/4ae91dd89e6c439d41cb87195cf13c597c6e5259))
* **ui-builder:** advance gate zero ([#88](https://github.com/yschimke/compose-ui-builder/issues/88)) ([ddd2a1f](https://github.com/yschimke/compose-ui-builder/commit/ddd2a1f2e22527d59802a626f3d4436a2afbefbc))
* **ui-builder:** advertise five components the catalog already renders ([#380](https://github.com/yschimke/compose-ui-builder/issues/380)) ([cb7f631](https://github.com/yschimke/compose-ui-builder/commit/cb7f6317614a90151f35e5fb44703c0f6499d581))
* **ui-builder:** an agent's grant is a delegation, and a design can be shared ([#468](https://github.com/yschimke/compose-ui-builder/issues/468)) ([10df77c](https://github.com/yschimke/compose-ui-builder/commit/10df77cf754e95071d6f9d62c187be86c3ed2311))
* **ui-builder:** author a Wear screen, and prove what it generates ([#360](https://github.com/yschimke/compose-ui-builder/issues/360)) ([a9bb053](https://github.com/yschimke/compose-ui-builder/commit/a9bb053da0de6f2e29ef53ffe64bef1665308e90))
* **ui-builder:** author align and weight as modifiers, not properties ([#396](https://github.com/yschimke/compose-ui-builder/issues/396)) ([5182c78](https://github.com/yschimke/compose-ui-builder/commit/5182c780d6fda3fa77d36e5ddab3e5dbd39d4a05))
* **ui-builder:** author every WearWidgetBrush background on the widget container ([#328](https://github.com/yschimke/compose-ui-builder/issues/328)) ([380ad4a](https://github.com/yschimke/compose-ui-builder/commit/380ad4ac64e9b21ee61505b47caf84fdee52df35))
* **ui-builder:** author the component record the Compose export needs ([#286](https://github.com/yschimke/compose-ui-builder/issues/286)) ([5c0bc18](https://github.com/yschimke/compose-ui-builder/commit/5c0bc1840d9a0e604a2759b1c4bea622494fd9b5))
* **ui-builder:** author Wear screens with a stadium ScreenScaffold stand-in ([#354](https://github.com/yschimke/compose-ui-builder/issues/354)) ([b6a6118](https://github.com/yschimke/compose-ui-builder/commit/b6a61180c9349300e5d6616b6a042db825db7d2c))
* **ui-builder:** author with the catalog's published Remote Compose documents ([#317](https://github.com/yschimke/compose-ui-builder/issues/317)) ([6b501fe](https://github.com/yschimke/compose-ui-builder/commit/6b501fe6e890f2fc6ade53d451fa0a5e43d8993a))
* **ui-builder:** bound runtime pressure ([9125f87](https://github.com/yschimke/compose-ui-builder/commit/9125f8774d564b5805032121caab08fe104f5fc8))
* **ui-builder:** build against a reference picture, and mark it up ([#318](https://github.com/yschimke/compose-ui-builder/issues/318)) ([5ca4c78](https://github.com/yschimke/compose-ui-builder/commit/5ca4c78fbe88cacc3f61b00456f0f8f9f8153a30))
* **ui-builder:** canvas-forward editor chrome on collapsible docks ([#340](https://github.com/yschimke/compose-ui-builder/issues/340)) ([1d1e09a](https://github.com/yschimke/compose-ui-builder/commit/1d1e09a50e1755e1e62e9bb4ee4075bc9ccea744))
* **ui-builder:** capture a component onto the reference, and build it back ([#322](https://github.com/yschimke/compose-ui-builder/issues/322)) ([0fa5939](https://github.com/yschimke/compose-ui-builder/commit/0fa5939279feb943a0e8ec81a733ddd17d096934))
* **ui-builder:** carry a design's chat thread on its comment notifications ([#608](https://github.com/yschimke/compose-ui-builder/issues/608)) ([cda53f0](https://github.com/yschimke/compose-ui-builder/commit/cda53f036b49c0271fa4f45ec03a1f35f53c074e))
* **ui-builder:** carry resolved icon outlines in the design ([#745](https://github.com/yschimke/compose-ui-builder/issues/745)) ([00db2e8](https://github.com/yschimke/compose-ui-builder/commit/00db2e8c748011fa9daf573959887e988d213c7a))
* **ui-builder:** carry unacknowledged comments on tool replies, and separate seen from settled ([#512](https://github.com/yschimke/compose-ui-builder/issues/512)) ([d7aee26](https://github.com/yschimke/compose-ui-builder/commit/d7aee2691b42f246394a055620c5759edec40d7f))
* **ui-builder:** compose Remote Compose documents ([#163](https://github.com/yschimke/compose-ui-builder/issues/163)) ([4ea075f](https://github.com/yschimke/compose-ui-builder/commit/4ea075ff2f8479a529dac6d665c2059402d78bac))
* **ui-builder:** connect live browser sessions ([#109](https://github.com/yschimke/compose-ui-builder/issues/109)) ([a817b7c](https://github.com/yschimke/compose-ui-builder/commit/a817b7c373db93345daeb3fdb10bd2a09721d781))
* **ui-builder:** create a design with POST or PUT, never a GET ([#342](https://github.com/yschimke/compose-ui-builder/issues/342)) ([e6f7be8](https://github.com/yschimke/compose-ui-builder/commit/e6f7be8a83106e244d7380332a1dee16a035a5d2))
* **ui-builder:** declare a screen's state when the design is created ([#245](https://github.com/yschimke/compose-ui-builder/issues/245)) ([a9e85f0](https://github.com/yschimke/compose-ui-builder/commit/a9e85f09f53c247cf4aeaf277d9cd368df66ea04))
* **ui-builder:** design the component-packs screens, and follow the chrome that moved ([#438](https://github.com/yschimke/compose-ui-builder/issues/438)) ([b8fc61d](https://github.com/yschimke/compose-ui-builder/commit/b8fc61da13581de3f6edbb347102736d58469503))
* **ui-builder:** discuss a design where it is built, and watch the discussion ([#351](https://github.com/yschimke/compose-ui-builder/issues/351)) ([829fc25](https://github.com/yschimke/compose-ui-builder/commit/829fc254d7dc77940037a496df4b16a23a4817ad))
* **ui-builder:** draw a component placed more than once ([#662](https://github.com/yschimke/compose-ui-builder/issues/662)) ([99dea87](https://github.com/yschimke/compose-ui-builder/commit/99dea874683d6502899dba499e3598f76eac354e))
* **ui-builder:** draw a loop over the design's own rows ([#667](https://github.com/yschimke/compose-ui-builder/issues/667)) ([40f2c79](https://github.com/yschimke/compose-ui-builder/commit/40f2c798221988b4a1a7e549a90906979208898d))
* **ui-builder:** draw each palette row as the component it inserts ([#410](https://github.com/yschimke/compose-ui-builder/issues/410)) ([cd0b21f](https://github.com/yschimke/compose-ui-builder/commit/cd0b21fd151875c1d18e8e65b592eb76255ad8b4))
* **ui-builder:** draw the history as revision thumbnails, and compare two of them ([#658](https://github.com/yschimke/compose-ui-builder/issues/658)) ([6a468d9](https://github.com/yschimke/compose-ui-builder/commit/6a468d905d9c1df46b147dc493acf3ad53ab3846))
* **ui-builder:** draw the real SupportingPaneScaffold in every constrained frame ([#788](https://github.com/yschimke/compose-ui-builder/issues/788)) ([6b023e5](https://github.com/yschimke/compose-ui-builder/commit/6b023e592ebdd8ccb70d25726131f4d9c15d99cc))
* **ui-builder:** draw the rectangular host container on the canvas and the native pane ([#605](https://github.com/yschimke/compose-ui-builder/issues/605)) ([efadf1a](https://github.com/yschimke/compose-ui-builder/commit/efadf1a856a74fd47bdd2633e40395323003300e))
* **ui-builder:** draw the round host container too ([#609](https://github.com/yschimke/compose-ui-builder/issues/609)) ([253d769](https://github.com/yschimke/compose-ui-builder/commit/253d769ee0e0f119094088c93fe0c8e950258ea3))
* **ui-builder:** draw Wear components with Wear Compose on the canvas ([#912](https://github.com/yschimke/compose-ui-builder/issues/912)) ([9a38b5a](https://github.com/yschimke/compose-ui-builder/commit/9a38b5aafc077743576a809410d958cc9739b010))
* **ui-builder:** edit a screen at its extent, beside the frame it ships at ([#458](https://github.com/yschimke/compose-ui-builder/issues/458)) ([33be7d7](https://github.com/yschimke/compose-ui-builder/commit/33be7d74adefa364ee0dbb85770beb6b4c3af6d4))
* **ui-builder:** embed Remote Compose in mobile and Wear designs, and Compose back inside it ([#460](https://github.com/yschimke/compose-ui-builder/issues/460)) ([50d8d1d](https://github.com/yschimke/compose-ui-builder/commit/50d8d1d585cf443d5b941541523c9a71c3d4722d))
* **ui-builder:** enforce runtime quotas ([831c01b](https://github.com/yschimke/compose-ui-builder/commit/831c01b78c27477befe1e43701b5bb4d3472c592))
* **ui-builder:** evolve catalogs and navigate designs ([#866](https://github.com/yschimke/compose-ui-builder/issues/866)) ([5906c9a](https://github.com/yschimke/compose-ui-builder/commit/5906c9a3813b16ef4cc609a984872ce56cabdd37))
* **ui-builder:** export a button's container colour, whichever style it is ([#417](https://github.com/yschimke/compose-ui-builder/issues/417)) ([67da23d](https://github.com/yschimke/compose-ui-builder/commit/67da23d844eed688cff89582d66e4885dfc5af64))
* **ui-builder:** export a layout weight, and matchParentSize in a box ([#385](https://github.com/yschimke/compose-ui-builder/issues/385)) ([3d57b35](https://github.com/yschimke/compose-ui-builder/commit/3d57b35a0d3aaa9e93d5901aa51583e4a3d2f03b))
* **ui-builder:** export a lazy list, row and grid instead of refusing them ([#414](https://github.com/yschimke/compose-ui-builder/issues/414)) ([39a703c](https://github.com/yschimke/compose-ui-builder/commit/39a703c922598fea4b18d5a08fa0877362cfba31))
* **ui-builder:** export a loop as its rows and one forEach ([#670](https://github.com/yschimke/compose-ui-builder/issues/670)) ([13f269e](https://github.com/yschimke/compose-ui-builder/commit/13f269edeb6c13ee6ce01f1dd45630be1e9c778c))
* **ui-builder:** export a placed component as its own composable ([#665](https://github.com/yschimke/compose-ui-builder/issues/665)) ([df3c325](https://github.com/yschimke/compose-ui-builder/commit/df3c325f2a99ae393c2780bb8238afb0178c2900))
* **ui-builder:** export a screen on the devices its design named ([#498](https://github.com/yschimke/compose-ui-builder/issues/498)) ([b85731b](https://github.com/yschimke/compose-ui-builder/commit/b85731b60203015e29cdbc987aa8074cc6856c37))
* **ui-builder:** export a text field and a progress indicator ([#399](https://github.com/yschimke/compose-ui-builder/issues/399)) ([c543908](https://github.com/yschimke/compose-ui-builder/commit/c5439089fe33e0ef266f9a104283d52a6940ecff))
* **ui-builder:** export a widget as a bundle, its pictures beside its source ([#533](https://github.com/yschimke/compose-ui-builder/issues/533)) ([b46fbe9](https://github.com/yschimke/compose-ui-builder/commit/b46fbe938f0afcd1242f56002e5fd6dacd51f4c8))
* **ui-builder:** export Remote Compose source for remote-m3 designs ([#364](https://github.com/yschimke/compose-ui-builder/issues/364)) ([55cecb8](https://github.com/yschimke/compose-ui-builder/commit/55cecb83943a8ce073a6105d03f7b3835e2c113b))
* **ui-builder:** export the determinate progress indicator ([#435](https://github.com/yschimke/compose-ui-builder/issues/435)) ([3d8e8c9](https://github.com/yschimke/compose-ui-builder/commit/3d8e8c96eb3895de82151a9eea1e07cc13071dc0))
* **ui-builder:** export to Figma from the toolbar, with live SVG and PNG links ([#425](https://github.com/yschimke/compose-ui-builder/issues/425)) ([efbf608](https://github.com/yschimke/compose-ui-builder/commit/efbf60811c8923b2c1d4247fef96b0501bd16137))
* **ui-builder:** expose complete Material icon catalog ([#710](https://github.com/yschimke/compose-ui-builder/issues/710)) ([ec1c4bf](https://github.com/yschimke/compose-ui-builder/commit/ec1c4bfe7ed319641cc7505c961c2ea71cc7db11))
* **ui-builder:** express every authored modifier, or refuse it by name ([#403](https://github.com/yschimke/compose-ui-builder/issues/403)) ([352f13a](https://github.com/yschimke/compose-ui-builder/commit/352f13a5d9654cca03885f2e494f2a4995a24f85))
* **ui-builder:** filter the layers panel, and take every match at once ([#242](https://github.com/yschimke/compose-ui-builder/issues/242)) ([4f5aea2](https://github.com/yschimke/compose-ui-builder/commit/4f5aea205ac07eb4c209141f09603bda61ec12d7))
* **ui-builder:** finish the m3 and remote-m3 cutover, and unstick main ([#713](https://github.com/yschimke/compose-ui-builder/issues/713)) ([21e36af](https://github.com/yschimke/compose-ui-builder/commit/21e36af4dc68a304f16c590457d4fa6f686e5b5b))
* **ui-builder:** five Google app sample designs, at three window sizes ([#902](https://github.com/yschimke/compose-ui-builder/issues/902)) ([bcfc60b](https://github.com/yschimke/compose-ui-builder/commit/bcfc60bdedefb4b51bb0c7b72aaa35dde75fa3fa))
* **ui-builder:** forward sandbox renderer input ([#140](https://github.com/yschimke/compose-ui-builder/issues/140)) ([85ba226](https://github.com/yschimke/compose-ui-builder/commit/85ba226b82dbdaec06c5f255a7523afa89e85ba2))
* **ui-builder:** forward to the permalink once a design is created ([#329](https://github.com/yschimke/compose-ui-builder/issues/329)) ([7beab87](https://github.com/yschimke/compose-ui-builder/commit/7beab8749e999e784c2b9547903c81f9598605fc))
* **ui-builder:** frame the design, zoom it, and cut the inspector down to the code ([#372](https://github.com/yschimke/compose-ui-builder/issues/372)) ([d314d6f](https://github.com/yschimke/compose-ui-builder/commit/d314d6f0c5153a9df5cc69b061dded508c2a6f55))
* **ui-builder:** freeze the synthesised catalogs and gate a catalog's readiness ([#596](https://github.com/yschimke/compose-ui-builder/issues/596)) ([f1a2a0a](https://github.com/yschimke/compose-ui-builder/commit/f1a2a0a6a05ad907d809b7a3661b0dc9b9ac667a))
* **ui-builder:** gate stateful Remote authoring behind a build flag ([#708](https://github.com/yschimke/compose-ui-builder/issues/708)) ([bc4c389](https://github.com/yschimke/compose-ui-builder/commit/bc4c389b4771cf154fa3da6ef187cec816a7ae52))
* **ui-builder:** generate a Wear widget's own Kotlin, not the fake container's ([#331](https://github.com/yschimke/compose-ui-builder/issues/331)) ([c184d8d](https://github.com/yschimke/compose-ui-builder/commit/c184d8d4f805c365459d799f47bd8a001695e548))
* **ui-builder:** generate the custom-component operation for an inline body ([#471](https://github.com/yschimke/compose-ui-builder/issues/471)) ([9e9932f](https://github.com/yschimke/compose-ui-builder/commit/9e9932fdb93bc311773a12ad5220ab88de210c69))
* **ui-builder:** generate the rectangular widget preview beside the squircle ([#597](https://github.com/yschimke/compose-ui-builder/issues/597)) ([a461b97](https://github.com/yschimke/compose-ui-builder/commit/a461b973f9c84da733bb95f4aa123b89e6f11217))
* **ui-builder:** give the builder's own vocabulary a component record of its own ([#850](https://github.com/yschimke/compose-ui-builder/issues/850)) ([6b420cd](https://github.com/yschimke/compose-ui-builder/commit/6b420cd37e8ea3d7063d132a68243f769eed41f8))
* **ui-builder:** group the component menu by catalog family and offer variants ([#405](https://github.com/yschimke/compose-ui-builder/issues/405)) ([94d19ff](https://github.com/yschimke/compose-ui-builder/commit/94d19ffe823c2d8bc270015cc8162ed63de7970c))
* **ui-builder:** harden SVG raster provenance ([#92](https://github.com/yschimke/compose-ui-builder/issues/92)) ([be65c28](https://github.com/yschimke/compose-ui-builder/commit/be65c28e996c2ee8681afef0b2a2f0844309ceaa))
* **ui-builder:** host pinned renderer runtimes ([ce5db4e](https://github.com/yschimke/compose-ui-builder/commit/ce5db4eb5a766b73a3d4251b0334c7f23ab25671))
* **ui-builder:** host standalone preview ([#89](https://github.com/yschimke/compose-ui-builder/issues/89)) ([bc02515](https://github.com/yschimke/compose-ui-builder/commit/bc02515198c0286fd283928f047a338b9fed9724))
* **ui-builder:** implement the state variable and event binding mutations ([#398](https://github.com/yschimke/compose-ui-builder/issues/398)) ([b094256](https://github.com/yschimke/compose-ui-builder/commit/b0942561eb390e70b341fd989c1ac53c2bbc71b7))
* **ui-builder:** inline a widget background picture, so a design with artwork exports ([#524](https://github.com/yschimke/compose-ui-builder/issues/524)) ([eb42065](https://github.com/yschimke/compose-ui-builder/commit/eb42065459caf954e22e6a247e348023413c124f))
* **ui-builder:** introduce a compose-foundation catalog as the builder-vocabulary donor ([#821](https://github.com/yschimke/compose-ui-builder/issues/821)) ([87e3aa8](https://github.com/yschimke/compose-ui-builder/commit/87e3aa8f5916475a82f50087a55f245d9f5e7e9c))
* **ui-builder:** keep a links record beside each design ([#598](https://github.com/yschimke/compose-ui-builder/issues/598)) ([cbdd3a5](https://github.com/yschimke/compose-ui-builder/commit/cbdd3a56416f7a448f106b43168f54543c2c5448))
* **ui-builder:** keep designs in the browser, edit them offline, and sync them back ([#536](https://github.com/yschimke/compose-ui-builder/issues/536)) ([58f6500](https://github.com/yschimke/compose-ui-builder/commit/58f6500b8dd0e934b56ce7b3e247c76b63db3a10))
* **ui-builder:** keep designs in the repository, and design the builder's screens with the builder ([#433](https://github.com/yschimke/compose-ui-builder/issues/433)) ([fe41334](https://github.com/yschimke/compose-ui-builder/commit/fe413349a87f26c54077b36ed34a92ed1803cadf))
* **ui-builder:** key the canvas dispatch on the catalog's adapter id ([#916](https://github.com/yschimke/compose-ui-builder/issues/916)) ([80cfa2b](https://github.com/yschimke/compose-ui-builder/commit/80cfa2b394e69d86ece54a77d669ca0d61c58290))
* **ui-builder:** let a button's style and an icon button's variant select components ([#392](https://github.com/yschimke/compose-ui-builder/issues/392)) ([78c4c92](https://github.com/yschimke/compose-ui-builder/commit/78c4c92af2adee9b4b8b9c6d270666d04ec31b8d))
* **ui-builder:** let a card's variant select the component it names ([#388](https://github.com/yschimke/compose-ui-builder/issues/388)) ([dffc295](https://github.com/yschimke/compose-ui-builder/commit/dffc2950b919cd22571e632d42ab0d9e9606d78a))
* **ui-builder:** let a catalog state its own vocabulary, not just its ids ([#664](https://github.com/yschimke/compose-ui-builder/issues/664)) ([1368a37](https://github.com/yschimke/compose-ui-builder/commit/1368a376c2b173a47b0d91326b929bd2f32fa6f9))
* **ui-builder:** let a client write a node's modifier chain, and lay a node out from its menu ([#378](https://github.com/yschimke/compose-ui-builder/issues/378)) ([0db18b1](https://github.com/yschimke/compose-ui-builder/commit/0db18b149c8a3d34707f748d5fc6b06c64183c40))
* **ui-builder:** let a design already open declare, place and drop a component ([#712](https://github.com/yschimke/compose-ui-builder/issues/712)) ([0fae5fb](https://github.com/yschimke/compose-ui-builder/commit/0fae5fb8a48520e359c016e37177212b92c692f6))
* **ui-builder:** let a design name the devices it exports as ([#465](https://github.com/yschimke/compose-ui-builder/issues/465)) ([f5d0276](https://github.com/yschimke/compose-ui-builder/commit/f5d0276ac1219e581afaf1136057302ac18bca95))
* **ui-builder:** let a design URL name a revision, node and thread ([#602](https://github.com/yschimke/compose-ui-builder/issues/602)) ([e79e68f](https://github.com/yschimke/compose-ui-builder/commit/e79e68fd0f9478a7786f0083dcfaca17cac6c47d))
* **ui-builder:** let a published Remote catalog declare a text size ([#845](https://github.com/yschimke/compose-ui-builder/issues/845)) ([3b1f975](https://github.com/yschimke/compose-ui-builder/commit/3b1f9750cd7dd891d1e5632750e3366db2ef77ce))
* **ui-builder:** let a tab row be clicked, on the canvas and in the export ([#659](https://github.com/yschimke/compose-ui-builder/issues/659)) ([fdda9e4](https://github.com/yschimke/compose-ui-builder/commit/fdda9e48e10570cae9317e54c9e4f8bfe90be045))
* **ui-builder:** let an agent wait for a design to change, rather than ask again ([#356](https://github.com/yschimke/compose-ui-builder/issues/356)) ([8c15a0e](https://github.com/yschimke/compose-ui-builder/commit/8c15a0eeb137d7022b53067f5fdaf481ad03e42d))
* **ui-builder:** let an operator repair a quarantined design, not only delete it ([#448](https://github.com/yschimke/compose-ui-builder/issues/448)) ([2ce5eac](https://github.com/yschimke/compose-ui-builder/commit/2ce5eac4ecb45b42ee43218d16570e67d5cefa06))
* **ui-builder:** let the canvas run the screen you just wired up ([#243](https://github.com/yschimke/compose-ui-builder/issues/243)) ([3d0d236](https://github.com/yschimke/compose-ui-builder/commit/3d0d236d94b6405c3ff299f8c7e57afcda3ebb15))
* **ui-builder:** let the chrome's text be selected and copied ([#367](https://github.com/yschimke/compose-ui-builder/issues/367)) ([dcf760a](https://github.com/yschimke/compose-ui-builder/commit/dcf760ac56807d8a0bda90f3f7aeabc329f3b102))
* **ui-builder:** let the inspector see a state binding ([#244](https://github.com/yschimke/compose-ui-builder/issues/244)) ([5daf75f](https://github.com/yschimke/compose-ui-builder/commit/5daf75fcca6024e5af133818835553e408fdcbf4))
* **ui-builder:** make /ui-builder/designs a file manager ([#927](https://github.com/yschimke/compose-ui-builder/issues/927)) ([2908f28](https://github.com/yschimke/compose-ui-builder/commit/2908f28d23833038aec68d77add2d47566b44a1f))
* **ui-builder:** make a published m3 catalog exportable — 83 of 110, from none ([#703](https://github.com/yschimke/compose-ui-builder/issues/703)) ([87ea993](https://github.com/yschimke/compose-ui-builder/commit/87ea993e2ed6bf0ca6dc793e9ef4412aaba06e80))
* **ui-builder:** make an ordinary screen export ([#494](https://github.com/yschimke/compose-ui-builder/issues/494)) ([b8ee04e](https://github.com/yschimke/compose-ui-builder/commit/b8ee04efd540cfbea62fe770db08c73a01a11d1d))
* **ui-builder:** make the builder usable for designing an interactive screen ([#238](https://github.com/yschimke/compose-ui-builder/issues/238)) ([5f4073c](https://github.com/yschimke/compose-ui-builder/commit/5f4073cb588f11d5e15b61aa0ca067c1a1d0ee59))
* **ui-builder:** make the catalog's layout properties editable ([#240](https://github.com/yschimke/compose-ui-builder/issues/240)) ([6705b56](https://github.com/yschimke/compose-ui-builder/commit/6705b5692140046ef6c234d213beaa14f4337edf))
* **ui-builder:** make the editor's shortcuts findable, from one table ([#239](https://github.com/yschimke/compose-ui-builder/issues/239)) ([1c9633c](https://github.com/yschimke/compose-ui-builder/commit/1c9633cba4e208e4af9625ca81cfe7dfddd764fa))
* **ui-builder:** make the Screen dock's device section explain itself ([#905](https://github.com/yschimke/compose-ui-builder/issues/905)) ([ba3a9aa](https://github.com/yschimke/compose-ui-builder/commit/ba3a9aa5d3499f3d2e711cfa9b4b9e08ca4036b1))
* **ui-builder:** make the workspace panes three switches, not a ladder ([#770](https://github.com/yschimke/compose-ui-builder/issues/770)) ([91c451d](https://github.com/yschimke/compose-ui-builder/commit/91c451d4c7f3ee9685e6e6973f6ec5a155165360))
* **ui-builder:** mark the palette rows the Compose export cannot write ([#502](https://github.com/yschimke/compose-ui-builder/issues/502)) ([c141d3e](https://github.com/yschimke/compose-ui-builder/commit/c141d3e5805a2c826768e98ad96bb1e45453d288))
* **ui-builder:** measure the canvas against instance paths, not node ids ([#661](https://github.com/yschimke/compose-ui-builder/issues/661)) ([3c6fbe5](https://github.com/yschimke/compose-ui-builder/commit/3c6fbe597e9cbf734ff5b2885b8fc8186cb5eed1))
* **ui-builder:** name a design in the path, not the query ([#327](https://github.com/yschimke/compose-ui-builder/issues/327)) ([931bf19](https://github.com/yschimke/compose-ui-builder/commit/931bf191c502347e5083c2628d12ac44076acfdd))
* **ui-builder:** offer other catalogs' components as platform-scoped packs ([#421](https://github.com/yschimke/compose-ui-builder/issues/421)) ([672a3a1](https://github.com/yschimke/compose-ui-builder/commit/672a3a1ca463c7386d88bc83b7d73e5bc77d9c97))
* **ui-builder:** open a new Material 3 screen on a phone, not the fixture's canvas ([#447](https://github.com/yschimke/compose-ui-builder/issues/447)) ([7df2a3f](https://github.com/yschimke/compose-ui-builder/commit/7df2a3fe18feaf46c73a2ad142222dcb2d94ecd4))
* **ui-builder:** persist collaborative designs ([#91](https://github.com/yschimke/compose-ui-builder/issues/91)) ([91417d5](https://github.com/yschimke/compose-ui-builder/commit/91417d53766cc7641fc0e09f5aa448806177831f))
* **ui-builder:** pick a device frame from the catalog the renderer uses ([#264](https://github.com/yschimke/compose-ui-builder/issues/264)) ([c0c27cf](https://github.com/yschimke/compose-ui-builder/commit/c0c27cfcd0f59b93d5165fc006ca3e591ad0b496))
* **ui-builder:** pick a renderer instead of always showing two ([#315](https://github.com/yschimke/compose-ui-builder/issues/315)) ([bdb4d97](https://github.com/yschimke/compose-ui-builder/commit/bdb4d973448614d7e06c8ffcb2a8c7e389a2abb2))
* **ui-builder:** play inline Remote Compose content by capturing it ([#504](https://github.com/yschimke/compose-ui-builder/issues/504)) ([e37e9de](https://github.com/yschimke/compose-ui-builder/commit/e37e9def943357349444c3090e93844ec4679610))
* **ui-builder:** post comment activity to an outbound webhook ([#599](https://github.com/yschimke/compose-ui-builder/issues/599)) ([27fcc72](https://github.com/yschimke/compose-ui-builder/commit/27fcc723c1dc5cd1ad20f87a3cf21939f5f08cee))
* **ui-builder:** put a picture in a design, end to end ([#503](https://github.com/yschimke/compose-ui-builder/issues/503)) ([9423153](https://github.com/yschimke/compose-ui-builder/commit/9423153b18758ec459e8db6c3d1704660a003a0e))
* **ui-builder:** reach the Remote record fallback, and check the two m3 fields nothing checked ([#691](https://github.com/yschimke/compose-ui-builder/issues/691)) ([eed856d](https://github.com/yschimke/compose-ui-builder/commit/eed856dae092e6f4a16c93710cdee42d7071723d))
* **ui-builder:** read a component pack's record from the served catalog ([#437](https://github.com/yschimke/compose-ui-builder/issues/437)) ([b588d9b](https://github.com/yschimke/compose-ui-builder/commit/b588d9b9e8e182a019119abe9ac684f206651982))
* **ui-builder:** read RemoveNodePropertyMutationV1, the unset in its own words ([#513](https://github.com/yschimke/compose-ui-builder/issues/513)) ([8791836](https://github.com/yschimke/compose-ui-builder/commit/8791836e308b0175e37956b1ada9e1b26090e56e))
* **ui-builder:** render a design with real Compose, tagged so it stays addressable ([#302](https://github.com/yschimke/compose-ui-builder/issues/302)) ([d8f3278](https://github.com/yschimke/compose-ui-builder/commit/d8f3278ad907df40d27b119e4cc9b1ffcaa16008))
* **ui-builder:** render a Wear widget on the native preview lane ([#535](https://github.com/yschimke/compose-ui-builder/issues/535)) ([6967115](https://github.com/yschimke/compose-ui-builder/commit/6967115fb7361d2c0f2c355be4cae592ad04dd72))
* **ui-builder:** render Jetcaster benchmark ([#84](https://github.com/yschimke/compose-ui-builder/issues/84)) ([bd755c2](https://github.com/yschimke/compose-ui-builder/commit/bd755c2888f0d4e04eea769c0b0b9b054ce032ac))
* **ui-builder:** render the modifier vocabulary, and edit a node where it is drawn ([#390](https://github.com/yschimke/compose-ui-builder/issues/390)) ([f71f09f](https://github.com/yschimke/compose-ui-builder/commit/f71f09fea03da89a047ef3a68c0375bf4fe743d1))
* **ui-builder:** render Wear designs on Android, and complete the Wear catalog ([#407](https://github.com/yschimke/compose-ui-builder/issues/407)) ([cd4075c](https://github.com/yschimke/compose-ui-builder/commit/cd4075c3a777fee22ac5ff438e4ca21c1d2725de))
* **ui-builder:** resolve catalog enum values and property names for Compose export ([#349](https://github.com/yschimke/compose-ui-builder/issues/349)) ([6bd0734](https://github.com/yschimke/compose-ui-builder/commit/6bd0734458f10e5914ca7da7c075190192f3c289))
* **ui-builder:** say what moving a design to another catalog would cost ([#844](https://github.com/yschimke/compose-ui-builder/issues/844)) ([49f18b5](https://github.com/yschimke/compose-ui-builder/commit/49f18b595908e5aa9fa9631637ddfd910ed6891e))
* **ui-builder:** say what undo would take back before you press it ([#538](https://github.com/yschimke/compose-ui-builder/issues/538)) ([2c0b0a8](https://github.com/yschimke/compose-ui-builder/commit/2c0b0a8e792c6c356671760fd91d00070648201c))
* **ui-builder:** say why an Add beside is refused, on the row that refuses ([#607](https://github.com/yschimke/compose-ui-builder/issues/607)) ([030d978](https://github.com/yschimke/compose-ui-builder/commit/030d978223e00f03f1f0439e6e07cd74716d9d70))
* **ui-builder:** seed inserted containers with typical default content ([#368](https://github.com/yschimke/compose-ui-builder/issues/368)) ([0341291](https://github.com/yschimke/compose-ui-builder/commit/034129173e1f7564ed35b30707ad99f15153f7b7))
* **ui-builder:** selectable overlays on the native render ([#319](https://github.com/yschimke/compose-ui-builder/issues/319)) ([ffd939e](https://github.com/yschimke/compose-ui-builder/commit/ffd939e57f15c153640961ff5cffd1ffae157dec))
* **ui-builder:** serve a catalog from its own published ui-builder.json ([#610](https://github.com/yschimke/compose-ui-builder/issues/610)) ([5d50f48](https://github.com/yschimke/compose-ui-builder/commit/5d50f485cecc3b8cde340d7576baeb157087a17f))
* **ui-builder:** serve remote-m3 from its published catalog ([#797](https://github.com/yschimke/compose-ui-builder/issues/797)) ([90b39b3](https://github.com/yschimke/compose-ui-builder/commit/90b39b3c48fbc35901d72d5ab5f68f661be5f284))
* **ui-builder:** serve wear-m3 from its published catalog ([#807](https://github.com/yschimke/compose-ui-builder/issues/807)) ([5a42eb9](https://github.com/yschimke/compose-ui-builder/commit/5a42eb98d18ea4df11b17f03f48438da93a74e1d))
* **ui-builder:** serve wear-m3 in the packaged deployment again ([#791](https://github.com/yschimke/compose-ui-builder/issues/791)) ([8c8d3ca](https://github.com/yschimke/compose-ui-builder/commit/8c8d3ca10cc43754fef3c7c582f5ff7a4f69a42a))
* **ui-builder:** set a theme typeface on a design, resolved by the host ([#381](https://github.com/yschimke/compose-ui-builder/issues/381)) ([0c8c541](https://github.com/yschimke/compose-ui-builder/commit/0c8c5418f705b7248a7d49d9d7ef749b3cbf0803))
* **ui-builder:** share project-owned offline artwork ([#98](https://github.com/yschimke/compose-ui-builder/issues/98)) ([68cb950](https://github.com/yschimke/compose-ui-builder/commit/68cb95042a60038623e1751b254658ead4f2d112))
* **ui-builder:** show live collaborator presence ([5932da1](https://github.com/yschimke/compose-ui-builder/commit/5932da1b49f86993568fea7041cd04fc64fd4569))
* **ui-builder:** show the host's native render beside the browser's canvas ([#304](https://github.com/yschimke/compose-ui-builder/issues/304)) ([46707c7](https://github.com/yschimke/compose-ui-builder/commit/46707c7672ef6bb46a6cd9cf52ca2d30e4346a4d))
* **ui-builder:** show the Kotlin an export would write, beside the canvas ([#295](https://github.com/yschimke/compose-ui-builder/issues/295)) ([021cbaf](https://github.com/yschimke/compose-ui-builder/commit/021cbaff160e5c6a23bbe745ff92d080bd960841))
* **ui-builder:** store designs per design, so an edit stops rewriting all of them ([#601](https://github.com/yschimke/compose-ui-builder/issues/601)) ([f10f706](https://github.com/yschimke/compose-ui-builder/commit/f10f706c3d60823b1bfdecf1deecdefb99beddfc))
* **ui-builder:** stream the native pane, live on Android ([#772](https://github.com/yschimke/compose-ui-builder/issues/772)) ([3fc2b77](https://github.com/yschimke/compose-ui-builder/commit/3fc2b77d669d673bd3e57a8a9b68ab4a85b5ab31))
* **ui-builder:** syntax-highlight the Code pane ([#335](https://github.com/yschimke/compose-ui-builder/issues/335)) ([a2be7b3](https://github.com/yschimke/compose-ui-builder/commit/a2be7b323f1e2eee7bbe886979f1af35689d3138))
* **ui-builder:** take wear-m3 out of the default allowlist, add a lever to flip it ([#628](https://github.com/yschimke/compose-ui-builder/issues/628)) ([0b5efff](https://github.com/yschimke/compose-ui-builder/commit/0b5efffc3541695eac36042af7ed74bc04a5816b))
* **ui-builder:** the editor and the export share one oracle ([#290](https://github.com/yschimke/compose-ui-builder/issues/290)) ([44f26a4](https://github.com/yschimke/compose-ui-builder/commit/44f26a40b16362c4c116dcb48f039be30feb8035))
* **ui-builder:** the Hello and Weather Wear widgets as worked templates ([#323](https://github.com/yschimke/compose-ui-builder/issues/323)) ([1aa1aa2](https://github.com/yschimke/compose-ui-builder/commit/1aa1aa2cffeb01c1cb458f106a5e9eca36852b2f))
* **ui-builder:** verify generated Compose export ([#93](https://github.com/yschimke/compose-ui-builder/issues/93)) ([823d8f8](https://github.com/yschimke/compose-ui-builder/commit/823d8f845c7e98631cc658c1b59e6b29668670b5))
* **ui-builder:** wire production persistence and exports ([#111](https://github.com/yschimke/compose-ui-builder/issues/111)) ([6c13753](https://github.com/yschimke/compose-ui-builder/commit/6c137531f2aae15e192ce017991fe7a1cf9bb82a))
* **ui-builder:** write `asset/image` on a Wear screen ([#922](https://github.com/yschimke/compose-ui-builder/issues/922)) ([57fb749](https://github.com/yschimke/compose-ui-builder/commit/57fb74917405fbfdc3a2436a8075820feb6df39d))
* **ui-builder:** write a Remote component from its record, and measure what the shelf exports ([#686](https://github.com/yschimke/compose-ui-builder/issues/686)) ([a4eff81](https://github.com/yschimke/compose-ui-builder/commit/a4eff8198109270d2f6e04064ba4c68af33e8468))
* **ui-builder:** write a Wear pack component from its record ([#444](https://github.com/yschimke/compose-ui-builder/issues/444)) ([07cbd25](https://github.com/yschimke/compose-ui-builder/commit/07cbd252acfa2a30cc6ef8032abe85a5e018b5b1))
* **ui-builder:** write the published text component with the m3/text hand ([#848](https://github.com/yschimke/compose-ui-builder/issues/848)) ([a479df5](https://github.com/yschimke/compose-ui-builder/commit/a479df5a3329fe644a40337c5f829c9fe5166fae))


### Bug Fixes

* **build:** check every published module's POM, not only the server's ([#454](https://github.com/yschimke/compose-ui-builder/issues/454)) ([a8aef7c](https://github.com/yschimke/compose-ui-builder/commit/a8aef7cc73685ed001e8b923effcb350a2b3fa76))
* **build:** collapse the js-joda lambdas ktfmt collapses ([7787326](https://github.com/yschimke/compose-ui-builder/commit/778732671415fd7ff56baca58c8ee816b96b0202))
* **build:** match wasm producer tasks case-insensitively so main goes green ([#631](https://github.com/yschimke/compose-ui-builder/issues/631)) ([b314188](https://github.com/yschimke/compose-ui-builder/commit/b314188f6c2bfddc6c5fdd5c55f4910fa664bb6c))
* **build:** name composePreviewBundle after the plugin registers it ([#725](https://github.com/yschimke/compose-ui-builder/issues/725)) ([63424da](https://github.com/yschimke/compose-ui-builder/commit/63424da9aec0e4fdbe0f6909b93327d5b65ab48a))
* **build:** order composePreviewBundle after the wasm producers too ([#632](https://github.com/yschimke/compose-ui-builder/issues/632)) ([a878b53](https://github.com/yschimke/compose-ui-builder/commit/a878b5300e3f918bdec2b4511620f8f35d390163))
* **ci:** restore decoder-shapes.json, which the equivalence gate reads ([18b4330](https://github.com/yschimke/compose-ui-builder/commit/18b4330039b7612ece4a9169ca0cf6dce6057577))
* clear every Kotlin compile warning in the build ([#569](https://github.com/yschimke/compose-ui-builder/issues/569)) ([478b560](https://github.com/yschimke/compose-ui-builder/commit/478b560114ef07f37d0860a2939d7baacc93108f))
* **deploy:** ship the component record, so a packaged builder can export ([#305](https://github.com/yschimke/compose-ui-builder/issues/305)) ([5eb82be](https://github.com/yschimke/compose-ui-builder/commit/5eb82be382d8e58ab6e571dce09ebca5221cbad9))
* **deploy:** stop forcing Maven Local ([#868](https://github.com/yschimke/compose-ui-builder/issues/868)) ([f3fe331](https://github.com/yschimke/compose-ui-builder/commit/f3fe331a79b311c5d4ba397168eff46978bd52b9))
* **deps:** consume compose-ai-tools 1.53.0 ([#5](https://github.com/yschimke/compose-ui-builder/issues/5)) ([5728b64](https://github.com/yschimke/compose-ui-builder/commit/5728b64ff57c52bb5b204a33877be4bb47565ff1))
* **deps:** take the compose-ai-tools release that names a curved run's font ([#210](https://github.com/yschimke/compose-ui-builder/issues/210)) ([086ecc1](https://github.com/yschimke/compose-ui-builder/commit/086ecc1e008ae0b76c171c11ee58c4e1d39f7f67))
* **deps:** take the daemon line to 3.6.1, whose release has its assets ([#892](https://github.com/yschimke/compose-ui-builder/issues/892)) ([2c59a6f](https://github.com/yschimke/compose-ui-builder/commit/2c59a6f4e9dc58c088dce449875e5749e55f08ce))
* **deps:** update compose-ai-tools ([#144](https://github.com/yschimke/compose-ui-builder/issues/144)) ([b664c0f](https://github.com/yschimke/compose-ui-builder/commit/b664c0ffa94679a9cb61d623c221ffe7042fbc41))
* **deps:** update compose-ai-tools ([#159](https://github.com/yschimke/compose-ui-builder/issues/159)) ([59b08a1](https://github.com/yschimke/compose-ui-builder/commit/59b08a1e57ef6001c98c296a51f807bc1fcac5d2))
* **deps:** update compose-ai-tools ([#169](https://github.com/yschimke/compose-ui-builder/issues/169)) ([d07111d](https://github.com/yschimke/compose-ui-builder/commit/d07111dcb06c19bcd64e4dca6146b868c898b009))
* **deps:** update compose-ai-tools ([#176](https://github.com/yschimke/compose-ui-builder/issues/176)) ([5b8a6e7](https://github.com/yschimke/compose-ui-builder/commit/5b8a6e78a1e28b9273e660ccc2f3dd473d1c88a0))
* **deps:** update compose-ai-tools ([#190](https://github.com/yschimke/compose-ui-builder/issues/190)) ([d04d0aa](https://github.com/yschimke/compose-ui-builder/commit/d04d0aa9e1ee5fb2f19807224424fe158740427c))
* **deps:** update compose-ai-tools ([#297](https://github.com/yschimke/compose-ui-builder/issues/297)) ([cf1190f](https://github.com/yschimke/compose-ui-builder/commit/cf1190f3b9ca088241be7a588136cb8a63ff0d81))
* **deps:** update compose-ai-tools ([#540](https://github.com/yschimke/compose-ui-builder/issues/540)) ([8470f4e](https://github.com/yschimke/compose-ui-builder/commit/8470f4eda46b85d7735a3de12e0fb9bab573cc5d))
* **deps:** update compose-ai-tools ([#565](https://github.com/yschimke/compose-ui-builder/issues/565)) ([820e941](https://github.com/yschimke/compose-ui-builder/commit/820e941b6b5284487b3d77c8e9981a192c240c5b))
* **deps:** update compose-ai-tools ([#739](https://github.com/yschimke/compose-ui-builder/issues/739)) ([97ecd6d](https://github.com/yschimke/compose-ui-builder/commit/97ecd6d39ec8474886461b94a6ab9efd18d8bfb0))
* **deps:** update compose-ai-tools ([#749](https://github.com/yschimke/compose-ui-builder/issues/749)) ([e40dd0e](https://github.com/yschimke/compose-ui-builder/commit/e40dd0edef7d632fc92face53ac2d8f105d27c19))
* **deps:** update compose-ai-tools to v1.71.0 ([#258](https://github.com/yschimke/compose-ui-builder/issues/258)) ([98c958b](https://github.com/yschimke/compose-ui-builder/commit/98c958b4dd3870fbd97767ab805d122fab84df87))
* **deps:** update compose-ai-tools to v1.79.0 ([#309](https://github.com/yschimke/compose-ui-builder/issues/309)) ([2e64777](https://github.com/yschimke/compose-ui-builder/commit/2e64777e8b20432b9563367e3d81779f8c5af2a3))
* **deps:** update compose-ai-tools to v2.11.1 ([#784](https://github.com/yschimke/compose-ui-builder/issues/784)) ([b12cfcc](https://github.com/yschimke/compose-ui-builder/commit/b12cfcc74c04ef2e773e99186c84b561ccaafd10))
* **deps:** update compose-ai-tools to v2.14.1 ([#871](https://github.com/yschimke/compose-ui-builder/issues/871)) ([3f4e395](https://github.com/yschimke/compose-ui-builder/commit/3f4e3953604fd87ded1888b86054178bc5290b09))
* **deps:** update compose-ai-tools to v2.15.0 ([#874](https://github.com/yschimke/compose-ui-builder/issues/874)) ([6699f0b](https://github.com/yschimke/compose-ui-builder/commit/6699f0b67abe6f4217927b2da662ce0aca8a343b))
* **deps:** update compose-ai-tools to v2.4.0 ([#616](https://github.com/yschimke/compose-ui-builder/issues/616)) ([ff161db](https://github.com/yschimke/compose-ui-builder/commit/ff161dbb3c7c5ec13a3f630935907b971a27a8ce))
* **deps:** update compose-ai-tools to v2.5.0 ([#684](https://github.com/yschimke/compose-ui-builder/issues/684)) ([cc3483b](https://github.com/yschimke/compose-ui-builder/commit/cc3483bdfb2e81e062a178930b5fa02d0bb899fc))
* **deps:** update compose-ai-tools to v2.8.0 ([#723](https://github.com/yschimke/compose-ui-builder/issues/723)) ([ab916fd](https://github.com/yschimke/compose-ui-builder/commit/ab916fd35d957208b4a658ca50768034f52fc5bb))
* **deps:** update compose-ai-tools to v3.2.0 ([#718](https://github.com/yschimke/compose-ui-builder/issues/718)) ([5d7020a](https://github.com/yschimke/compose-ui-builder/commit/5d7020ae27215b4a4448e59ac2bc402c19032a67))
* **deps:** update compose-multiplatform to v1.12.0 ([#731](https://github.com/yschimke/compose-ui-builder/issues/731)) ([02d177b](https://github.com/yschimke/compose-ui-builder/commit/02d177b3e00ce450f21d0430fbd925296d927235))
* **deps:** update compose-preview-contracts to v2.11.0 ([#541](https://github.com/yschimke/compose-ui-builder/issues/541)) ([9c00f86](https://github.com/yschimke/compose-ui-builder/commit/9c00f860ee02561bcfd04ce2180d264d96d57be4))
* **deps:** update compose-preview-contracts to v2.17.0 ([#740](https://github.com/yschimke/compose-ui-builder/issues/740)) ([c282c53](https://github.com/yschimke/compose-ui-builder/commit/c282c53f69d0de2797e0e95e5508a648cfe1a422))
* **deps:** update compose-preview-contracts to v2.20.0 ([#883](https://github.com/yschimke/compose-ui-builder/issues/883)) ([6c053b4](https://github.com/yschimke/compose-ui-builder/commit/6c053b4cb5ad9167e1b5f8ff889c30f2fbcb1c60))
* **deps:** update compose-preview-contracts to v2.3.0 ([#145](https://github.com/yschimke/compose-ui-builder/issues/145)) ([49013d8](https://github.com/yschimke/compose-ui-builder/commit/49013d8c9ea7dcd53fc83b4fb8b4e017fb3a7200))
* **deps:** update compose-preview-daemon ([#935](https://github.com/yschimke/compose-ui-builder/issues/935)) ([2d4ed03](https://github.com/yschimke/compose-ui-builder/commit/2d4ed03edced1aa291ca5e1e864e288a3480c733))
* **deps:** update compose-preview-daemon to v3.4.3 ([#781](https://github.com/yschimke/compose-ui-builder/issues/781)) ([1114cf5](https://github.com/yschimke/compose-ui-builder/commit/1114cf5ae79457f05d3b47e4600851229c2b011f))
* **deps:** update compose-preview-daemon to v3.4.5 ([#843](https://github.com/yschimke/compose-ui-builder/issues/843)) ([35c65a0](https://github.com/yschimke/compose-ui-builder/commit/35c65a037157b20dbfd31d632f194783889c4f1a))
* **deps:** update compose-preview-daemon to v3.4.7 ([#870](https://github.com/yschimke/compose-ui-builder/issues/870)) ([9e2c18f](https://github.com/yschimke/compose-ui-builder/commit/9e2c18feb8198e841666378cfb917d9c11e8bfee))
* **deps:** update compose-preview-daemon to v3.4.9 ([#878](https://github.com/yschimke/compose-ui-builder/issues/878)) ([754bd5a](https://github.com/yschimke/compose-ui-builder/commit/754bd5adc96b4d23cdd06f6600f306e2d81fb480))
* **deps:** update composeai.tools to v1.53.1 ([#23](https://github.com/yschimke/compose-ui-builder/issues/23)) ([c658ee4](https://github.com/yschimke/compose-ui-builder/commit/c658ee4ba9ec9a13581bace5a4ff2f5e521c1c81))
* **deps:** update composeai.tools to v1.54.0 ([#51](https://github.com/yschimke/compose-ui-builder/issues/51)) ([ec1f758](https://github.com/yschimke/compose-ui-builder/commit/ec1f758f3f068aa68c20a96b9e7005d9e3d20609))
* **deps:** update dependency com.squareup.okio:okio-fakefilesystem to v3.18.2 ([#296](https://github.com/yschimke/compose-ui-builder/issues/296)) ([722294b](https://github.com/yschimke/compose-ui-builder/commit/722294b8750c702fd6e7ca69bb1eff9ffff9fbdd))
* **deps:** update dependency dev.snipme:highlights to v1.1.0 ([#500](https://github.com/yschimke/compose-ui-builder/issues/500)) ([d32aa59](https://github.com/yschimke/compose-ui-builder/commit/d32aa590e3a9bcbd53957966b87b395b1c5edfe3))
* **deps:** update dependency ee.schimke.composeai:rc-player-compose to v1.56.1 ([#253](https://github.com/yschimke/compose-ui-builder/issues/253)) ([56a4e64](https://github.com/yschimke/compose-ui-builder/commit/56a4e64199637e68eaface350104357078107fe8))
* **deps:** update dependency ee.schimke.composeai:rc-player-compose to v1.57.0 ([#298](https://github.com/yschimke/compose-ui-builder/issues/298)) ([368e3d6](https://github.com/yschimke/compose-ui-builder/commit/368e3d6ef5c1d4c3a61ca7b4b70971e9855d02d7))
* **deps:** update dependency ee.schimke.composeai:rc-player-compose to v1.59.0 ([#501](https://github.com/yschimke/compose-ui-builder/issues/501)) ([8592b57](https://github.com/yschimke/compose-ui-builder/commit/8592b575f2729ff05c4b1e905aacbb39e542c755))
* **deps:** update dependency ee.schimke.composeai:rc-player-compose to v1.59.1 ([#519](https://github.com/yschimke/compose-ui-builder/issues/519)) ([0ca1144](https://github.com/yschimke/compose-ui-builder/commit/0ca11445367b0d03d486fd5794d4d1e4c23bc729))
* **deps:** update dependency ee.schimke.composeai:rc-player-compose to v1.60.0 ([#617](https://github.com/yschimke/compose-ui-builder/issues/617)) ([32468de](https://github.com/yschimke/compose-ui-builder/commit/32468debaf7fd15720048053772ca99b4e69b2c9))
* **deps:** update dependency ee.schimke.composeai:rc-player-compose to v1.60.1 ([#682](https://github.com/yschimke/compose-ui-builder/issues/682)) ([c0758e2](https://github.com/yschimke/compose-ui-builder/commit/c0758e23de6aa34e05c15fdbba4241bb5877387c))
* **deps:** update dependency ee.schimke.composeai:rc-player-compose to v1.60.2 ([#716](https://github.com/yschimke/compose-ui-builder/issues/716)) ([74c6695](https://github.com/yschimke/compose-ui-builder/commit/74c669569812e2c3d4df55266ee170566bd8976a))
* **deps:** update dependency ee.schimke.composeai:rc-player-compose to v1.61.1 ([#785](https://github.com/yschimke/compose-ui-builder/issues/785)) ([b1f4c98](https://github.com/yschimke/compose-ui-builder/commit/b1f4c98a6600161798d857a0e661b2f224998b7b))
* **deps:** update dependency ee.schimke.composeai:rc-player-compose to v1.63.0 ([#884](https://github.com/yschimke/compose-ui-builder/issues/884)) ([de9444a](https://github.com/yschimke/compose-ui-builder/commit/de9444a3ceb0baaa23b20b72ea1a8264973be19e))
* **deps:** update dependency io.github.classgraph:classgraph to v4.8.195 ([#566](https://github.com/yschimke/compose-ui-builder/issues/566)) ([64d88f3](https://github.com/yschimke/compose-ui-builder/commit/64d88f343f61d74eb82c51772ffda822892f5ec8))
* **deps:** update kotlin to v2.4.20 ([#717](https://github.com/yschimke/compose-ui-builder/issues/717)) ([918367f](https://github.com/yschimke/compose-ui-builder/commit/918367f15eff96ac60456802181a31062ce661ae))
* **deps:** update ktor to v3.6.0 ([#938](https://github.com/yschimke/compose-ui-builder/issues/938)) ([7f66e6b](https://github.com/yschimke/compose-ui-builder/commit/7f66e6bfbb53fc66411219e6a9f77568b70f9364))
* **deps:** update rc-player-compose to 1.59.3 ([#539](https://github.com/yschimke/compose-ui-builder/issues/539)) ([590622b](https://github.com/yschimke/compose-ui-builder/commit/590622b5717504a30ebcf2e390922e337a0a18dd))
* **gate:** restore the bomMembers configuration the gate resolves ([#7](https://github.com/yschimke/compose-ui-builder/issues/7)) ([badb608](https://github.com/yschimke/compose-ui-builder/commit/badb6085bd71751feb01865f27b6a5a066a96d3c))
* get main green after the extraction ([8cfab9b](https://github.com/yschimke/compose-ui-builder/commit/8cfab9bdd5db539f8d94ad0bf00d44d8e088a889))
* **harness:** converge the Jetcaster oracle on the design's pane widths ([#928](https://github.com/yschimke/compose-ui-builder/issues/928)) ([a45d3a3](https://github.com/yschimke/compose-ui-builder/commit/a45d3a36a307a19bb323574bcee681fc6f4a52f0))
* **harness:** draw the real SupportingPaneScaffold in the Jetcaster oracle ([#806](https://github.com/yschimke/compose-ui-builder/issues/806)) ([673ba06](https://github.com/yschimke/compose-ui-builder/commit/673ba06554b38c56519dcfc58794aff49d1cd2ec))
* move the tools line to 2.17.0 with the daemon bump ([#890](https://github.com/yschimke/compose-ui-builder/issues/890)) ([2fd253d](https://github.com/yschimke/compose-ui-builder/commit/2fd253d78982a53d451d1418facbb0a6fb61f50d))
* **preview:** align comparison and editor controls ([#700](https://github.com/yschimke/compose-ui-builder/issues/700)) ([d9d64d2](https://github.com/yschimke/compose-ui-builder/commit/d9d64d2244d2949b04ad9a0eacfa52c6ed064fc7))
* **rc-fonts:** vendor Inter, so the four conference theme specimens render ([#387](https://github.com/yschimke/compose-ui-builder/issues/387)) ([7f58ebd](https://github.com/yschimke/compose-ui-builder/commit/7f58ebd26d4236f38ecb2db80ac79f779f7920a7))
* **release:** derive the Maven publish set, and split the release lanes ([#449](https://github.com/yschimke/compose-ui-builder/issues/449)) ([c28977a](https://github.com/yschimke/compose-ui-builder/commit/c28977ab3e56ac8f04a7f630935c5c9080a8936b))
* **reporting:** scope preview issue metadata ([#174](https://github.com/yschimke/compose-ui-builder/issues/174)) ([27878c5](https://github.com/yschimke/compose-ui-builder/commit/27878c55f28d781c4b11f335420409ffbdedf9fd))
* **serve:** migrate the icon aliases and the baked-in fills correctly ([#742](https://github.com/yschimke/compose-ui-builder/issues/742)) ([88b1a6f](https://github.com/yschimke/compose-ui-builder/commit/88b1a6ff6fd28120f9b8c580a36f298d1e3d07e7))
* **serve:** never let a UI-builder persistence failure abort startup ([#577](https://github.com/yschimke/compose-ui-builder/issues/577)) ([d03653d](https://github.com/yschimke/compose-ui-builder/commit/d03653d7fb6bdd6262c6cb57795055dbdeaadd15))
* **serve:** refuse a cyclic symbol, and search every source a system has ([#688](https://github.com/yschimke/compose-ui-builder/issues/688)) ([96ccbea](https://github.com/yschimke/compose-ui-builder/commit/96ccbeabb916de72eaeed48b3a2716400460179e))
* **serve:** resolve the RC replay's density from the device too ([#482](https://github.com/yschimke/compose-ui-builder/issues/482)) ([3270b0b](https://github.com/yschimke/compose-ui-builder/commit/3270b0b924e92720fa9a7ff51c50c117c1d6497e))
* take daemon 3.4.8 ([#872](https://github.com/yschimke/compose-ui-builder/issues/872)) ([7eeabff](https://github.com/yschimke/compose-ui-builder/commit/7eeabff5c80223f21fa6716bd392c32b76138325))
* **test:** bring across the documents :ui-builder-export's tests read ([b693a73](https://github.com/yschimke/compose-ui-builder/commit/b693a7300553c3ef44b3f9f1b5c9c20e2db184fa))
* **test:** follow the pages chip's rename to "N design pages" ([#560](https://github.com/yschimke/compose-ui-builder/issues/560)) ([1ce0535](https://github.com/yschimke/compose-ui-builder/commit/1ce0535bf6baf4070b3fec8b3840af9d2b3ed8e6))
* **ui-builder-export:** format PropertyValueKinds, which is red on main ([#909](https://github.com/yschimke/compose-ui-builder/issues/909)) ([3ad320f](https://github.com/yschimke/compose-ui-builder/commit/3ad320f0dc563703f6a0429d70367911ea9e552f))
* **ui-builder-export:** publish it, so compose-preview-serve resolves ([#316](https://github.com/yschimke/compose-ui-builder/issues/316)) ([fef24f2](https://github.com/yschimke/compose-ui-builder/commit/fef24f2e6396aa4467a9d1da6b737a357832c0f9))
* **ui-builder-runtime:** checksum the persistence payload as stored ([#411](https://github.com/yschimke/compose-ui-builder/issues/411)) ([59cab62](https://github.com/yschimke/compose-ui-builder/commit/59cab62f26697d92682cee73ff1e02fcb5f58ebc))
* **ui-builder-runtime:** name the designs a host cannot serve, not just how many ([#440](https://github.com/yschimke/compose-ui-builder/issues/440)) ([5e59804](https://github.com/yschimke/compose-ui-builder/commit/5e59804137a208cf82b6b5e37d690f67ec45b17f))
* **ui-builder-runtime:** refresh the ABI dump for the per-catalog export predicate ([#313](https://github.com/yschimke/compose-ui-builder/issues/313)) ([5b698c4](https://github.com/yschimke/compose-ui-builder/commit/5b698c4436b731983533978ecf617dafc6768bdc))
* **ui-builder-runtime:** trim a repair candidate's slot, not just its node map ([#452](https://github.com/yschimke/compose-ui-builder/issues/452)) ([92b3f82](https://github.com/yschimke/compose-ui-builder/commit/92b3f8231cebc55cefd0fac553a28939000ee384))
* **ui-builder:** a component root is not a second parent of its own body ([#720](https://github.com/yschimke/compose-ui-builder/issues/720)) ([6241c93](https://github.com/yschimke/compose-ui-builder/commit/6241c939f0c482fde746fb0464bf0b75f4b8c55e))
* **ui-builder:** accept a design pinned to the catalog's other source ([#816](https://github.com/yschimke/compose-ui-builder/issues/816)) ([b0c2bf8](https://github.com/yschimke/compose-ui-builder/commit/b0c2bf8cf32fcf92ef62a6896919d979e9a6b3bb))
* **ui-builder:** align compact Jetcaster fidelity ([231553d](https://github.com/yschimke/compose-ui-builder/commit/231553dafaf43032f2ee297d2bf0b8b375284b66))
* **ui-builder:** Apply screen settings no longer clears the export devices ([#904](https://github.com/yschimke/compose-ui-builder/issues/904)) ([8192269](https://github.com/yschimke/compose-ui-builder/commit/8192269ee0523cdbebc2bb6977c63ca58288be40))
* **ui-builder:** authenticate live browser sessions ([cbe79a5](https://github.com/yschimke/compose-ui-builder/commit/cbe79a5a694ee447e4f76f1b6906c10be20257e7))
* **ui-builder:** bound a design's roots, and give the widget background a brush to hold ([#450](https://github.com/yschimke/compose-ui-builder/issues/450)) ([aa37a22](https://github.com/yschimke/compose-ui-builder/commit/aa37a229579962a2267e5d036bf0fdb44f77e6dc))
* **ui-builder:** bound retained revisions by bytes and report state headroom ([#573](https://github.com/yschimke/compose-ui-builder/issues/573)) ([08f64b0](https://github.com/yschimke/compose-ui-builder/commit/08f64b0640216890df1ac991a996710fe43e04a3))
* **ui-builder:** bound undo state by bytes and raise the state ceiling ([#575](https://github.com/yschimke/compose-ui-builder/issues/575)) ([64e5b39](https://github.com/yschimke/compose-ui-builder/commit/64e5b39e4e2d420c7e435d945367220f997ad7a6))
* **ui-builder:** capture a component with the catalog's ids in scope ([#734](https://github.com/yschimke/compose-ui-builder/issues/734)) ([7db8a73](https://github.com/yschimke/compose-ui-builder/commit/7db8a7324543b4cdc564fa2c1eef8a12c4212e38))
* **ui-builder:** carry the asset registry across the language boundary, and rehash the fixtures ([#514](https://github.com/yschimke/compose-ui-builder/issues/514)) ([c677058](https://github.com/yschimke/compose-ui-builder/commit/c677058ce22fb17b27efae8fd4ef6088c81cda31))
* **ui-builder:** check a stored design when it is used, not when the service starts ([#439](https://github.com/yschimke/compose-ui-builder/issues/439)) ([ddd8ed5](https://github.com/yschimke/compose-ui-builder/commit/ddd8ed5ede393b0b5dec4a7a9f8f2ec1a6062bca))
* **ui-builder:** collapse legacy case-variant grants before authorizing ([#625](https://github.com/yschimke/compose-ui-builder/issues/625)) ([4a1a65e](https://github.com/yschimke/compose-ui-builder/commit/4a1a65e3732c1daacab5d740886350709501a3e9))
* **ui-builder:** compose a card's content in a Box in the record-driven export, as the canvas draws it ([#506](https://github.com/yschimke/compose-ui-builder/issues/506)) ([20dff0c](https://github.com/yschimke/compose-ui-builder/commit/20dff0c67f11f0cc4c96c46db10b698ff33e0936))
* **ui-builder:** consolidate mutable test clock ([a40fb8a](https://github.com/yschimke/compose-ui-builder/commit/a40fb8af0be586a3829f0dcd3b219a0fb3919fdf))
* **ui-builder:** cover the accepted window with conflict history, and keep undo targets ([#637](https://github.com/yschimke/compose-ui-builder/issues/637)) ([d7ca56d](https://github.com/yschimke/compose-ui-builder/commit/d7ca56d28870f502b4593979bcd36d2390fa9b68))
* **ui-builder:** declare the namespace parts before the union that reads them ([#824](https://github.com/yschimke/compose-ui-builder/issues/824)) ([1cf09f1](https://github.com/yschimke/compose-ui-builder/commit/1cf09f18013eb649ad45066d7714880025df9829))
* **ui-builder:** decode before comparing, rather than patching one bad shape at a time ([#656](https://github.com/yschimke/compose-ui-builder/issues/656)) ([9874f89](https://github.com/yschimke/compose-ui-builder/commit/9874f890e9c0bf0d0f300b7ea8b8c7bc3a61dd0f))
* **ui-builder:** default the published-catalog path to none, m3-catalog is the one at risk ([#629](https://github.com/yschimke/compose-ui-builder/issues/629)) ([254101b](https://github.com/yschimke/compose-ui-builder/commit/254101b38a1e8ac29c063202c51d16abdbde4f99))
* **ui-builder:** draw why a design will not open, instead of a white page ([#836](https://github.com/yschimke/compose-ui-builder/issues/836)) ([97dd014](https://github.com/yschimke/compose-ui-builder/commit/97dd014c035add1806a31c7cbeba67daca9104e3))
* **ui-builder:** drive the SVG recorder's frames on Compose 1.12.0 ([#735](https://github.com/yschimke/compose-ui-builder/issues/735)) ([28959cd](https://github.com/yschimke/compose-ui-builder/commit/28959cd6d3706ab54c09d77d0892abfa9fad834f))
* **ui-builder:** emit a six-digit colour opaque, so a generated widget is not invisible ([#517](https://github.com/yschimke/compose-ui-builder/issues/517)) ([f7918d0](https://github.com/yschimke/compose-ui-builder/commit/f7918d0e0eb49129f5de36337262d794408069b6))
* **ui-builder:** export and render the designs this repository ships ([#930](https://github.com/yschimke/compose-ui-builder/issues/930)) ([8643f5f](https://github.com/yschimke/compose-ui-builder/commit/8643f5f4576cccc85aeeed41137f68f2843cf9d9))
* **ui-builder:** export icons, card containers and one canonical enum wrapper ([#363](https://github.com/yschimke/compose-ui-builder/issues/363)) ([02ac870](https://github.com/yschimke/compose-ui-builder/commit/02ac8701d0f834f1cec98eb3d85908968382c406))
* **ui-builder:** export the document type its own API is written in ([#348](https://github.com/yschimke/compose-ui-builder/issues/348)) ([9ae9eb9](https://github.com/yschimke/compose-ui-builder/commit/9ae9eb9e402c168bb6d9bc23bac69e60119ad0d0))
* **ui-builder:** feed the drift report to the editor and stop calling it a blocker ([#705](https://github.com/yschimke/compose-ui-builder/issues/705)) ([b96ed32](https://github.com/yschimke/compose-ui-builder/commit/b96ed326494bfc8e0661bc88e14d48e99ae86a4c))
* **ui-builder:** fit full icon catalog in CI ([#711](https://github.com/yschimke/compose-ui-builder/issues/711)) ([3d3478e](https://github.com/yschimke/compose-ui-builder/commit/3d3478e17ae1e68305412725874d595ad48acb74))
* **ui-builder:** gate the adaptive export, and stop a supporting-only design drawing blank ([#789](https://github.com/yschimke/compose-ui-builder/issues/789)) ([ae12e6e](https://github.com/yschimke/compose-ui-builder/commit/ae12e6e64f3c3f2e91f7e671af95b0d0c15d2d28))
* **ui-builder:** gate the render bundle on the coordinates the renderer needs ([#813](https://github.com/yschimke/compose-ui-builder/issues/813)) ([f20d198](https://github.com/yschimke/compose-ui-builder/commit/f20d1981683069bba9ef77867ebc4e5be002af9d))
* **ui-builder:** generate a widget preview that renders at the widget's footprint ([#446](https://github.com/yschimke/compose-ui-builder/issues/446)) ([7bdecd7](https://github.com/yschimke/compose-ui-builder/commit/7bdecd7cd14305bd5120dd43513a00d607a999e2))
* **ui-builder:** give a generated row the alignment the canvas gives it ([#525](https://github.com/yschimke/compose-ui-builder/issues/525)) ([b90db46](https://github.com/yschimke/compose-ui-builder/commit/b90db4649a7875f29e12e64d6a99518c9e572656))
* **ui-builder:** handle the BUNDLE export format the contracts bump added ([#542](https://github.com/yschimke/compose-ui-builder/issues/542)) ([930358e](https://github.com/yschimke/compose-ui-builder/commit/930358e81b4b873119d9b202161b0b2c93088d94))
* **ui-builder:** hide theme metadata from properties ([#162](https://github.com/yschimke/compose-ui-builder/issues/162)) ([3daac39](https://github.com/yschimke/compose-ui-builder/commit/3daac39b025ac223905c853e924ddf5a0cf16533))
* **ui-builder:** hold a component placement to the body it draws ([#715](https://github.com/yschimke/compose-ui-builder/issues/715)) ([968e350](https://github.com/yschimke/compose-ui-builder/commit/968e3500e5948998550706f69f4787a090f34661))
* **ui-builder:** honour the arrangement and alignment the catalog declares ([#343](https://github.com/yschimke/compose-ui-builder/issues/343)) ([d4698f0](https://github.com/yschimke/compose-ui-builder/commit/d4698f0c914f358d6686f0cf3b49c23365f3ca5f))
* **ui-builder:** improve Jetcaster detail fidelity ([9c26254](https://github.com/yschimke/compose-ui-builder/commit/9c26254b1f3964fdc95dfd94f4bf1d0bdf232823))
* **ui-builder:** improve Jetcaster render fidelity ([#112](https://github.com/yschimke/compose-ui-builder/issues/112)) ([47300b3](https://github.com/yschimke/compose-ui-builder/commit/47300b3712169270c91a67f048db5e38ab792dfd))
* **ui-builder:** keep the beside refusal to the row it belongs to ([#619](https://github.com/yschimke/compose-ui-builder/issues/619)) ([5222cf6](https://github.com/yschimke/compose-ui-builder/commit/5222cf61ced79dea0b7b5233e1bf73b4262e82bc))
* **ui-builder:** keep the store's message off status.json, and regenerate the ABI dump ([#833](https://github.com/yschimke/compose-ui-builder/issues/833)) ([a7bf214](https://github.com/yschimke/compose-ui-builder/commit/a7bf2149202fa9c05ea229762c196843de608eb9))
* **ui-builder:** lay the canvas frame out in the design's pixels, not the browser's ([#531](https://github.com/yschimke/compose-ui-builder/issues/531)) ([ad97c24](https://github.com/yschimke/compose-ui-builder/commit/ad97c24e2d753ff6e5ca9474f74fae24a747d6b4))
* **ui-builder:** let a catalog take a property away without killing the design ([#840](https://github.com/yschimke/compose-ui-builder/issues/840)) ([1f5d184](https://github.com/yschimke/compose-ui-builder/commit/1f5d184eb328cd30f50940357f174c5cb724b370))
* **ui-builder:** let the browser editor learn its authenticated actor ([#275](https://github.com/yschimke/compose-ui-builder/issues/275)) ([035dc6f](https://github.com/yschimke/compose-ui-builder/commit/035dc6f77555665cdb881bcce1e7c6669f63609f))
* **ui-builder:** let the Confetti design validate and export again ([#401](https://github.com/yschimke/compose-ui-builder/issues/401)) ([4ee22b4](https://github.com/yschimke/compose-ui-builder/commit/4ee22b460df379f09f36ca819e3208c96ce3ebd9))
* **ui-builder:** make editor usable on mobile ([#158](https://github.com/yschimke/compose-ui-builder/issues/158)) ([821606c](https://github.com/yschimke/compose-ui-builder/commit/821606c541a1c786e24db74177d22f90bb42e817))
* **ui-builder:** make remote component picker visual ([#671](https://github.com/yschimke/compose-ui-builder/issues/671)) ([eb86483](https://github.com/yschimke/compose-ui-builder/commit/eb86483bfa95dab9416925fc558a860df214fcc4))
* **ui-builder:** make restart exports deterministic ([#99](https://github.com/yschimke/compose-ui-builder/issues/99)) ([af5cf67](https://github.com/yschimke/compose-ui-builder/commit/af5cf67744427fdc991aaf39a317f2b53b3ae564))
* **ui-builder:** make the export tests read the build feature they depend on ([#792](https://github.com/yschimke/compose-ui-builder/issues/792)) ([1bea1c7](https://github.com/yschimke/compose-ui-builder/commit/1bea1c7f3f78cb3d408707e02025da766983e47e))
* **ui-builder:** match a github grant to its actor, and write the widget modifiers a design uses ([#589](https://github.com/yschimke/compose-ui-builder/issues/589)) ([cb1e37b](https://github.com/yschimke/compose-ui-builder/commit/cb1e37ba4f42f8fc9bedfe7f8561dc13188f42eb))
* **ui-builder:** model the Wear widget scaffold on WearWidgetContainer ([#325](https://github.com/yschimke/compose-ui-builder/issues/325)) ([11ae9eb](https://github.com/yschimke/compose-ui-builder/commit/11ae9eb5ed601869afb3785e94feeea09a3c853b))
* **ui-builder:** name a component the canvas cannot draw, don't call it an error ([#727](https://github.com/yschimke/compose-ui-builder/issues/727)) ([03ab787](https://github.com/yschimke/compose-ui-builder/commit/03ab7878f65914918b959280b4a6382401770ccd))
* **ui-builder:** name each slot in the layers panel, and land the layer drag ([#334](https://github.com/yschimke/compose-ui-builder/issues/334)) ([b147575](https://github.com/yschimke/compose-ui-builder/commit/b1475758f2fb1a080f24df3e46d9594ab715f9e8))
* **ui-builder:** point EditorLayerDragTest at the renamed capability fixture ([#347](https://github.com/yschimke/compose-ui-builder/issues/347)) ([6df0c31](https://github.com/yschimke/compose-ui-builder/commit/6df0c319ba06bff0d99d03516cf92912de6910b0))
* **ui-builder:** preserve generated Compose fields ([#95](https://github.com/yschimke/compose-ui-builder/issues/95)) ([6004dfe](https://github.com/yschimke/compose-ui-builder/commit/6004dfe131b5fd40f48ef664e78c7991d5191a79))
* **ui-builder:** preserve SVG typography provenance ([#105](https://github.com/yschimke/compose-ui-builder/issues/105)) ([b29a47e](https://github.com/yschimke/compose-ui-builder/commit/b29a47e014db197ec5c6ce7463cf914c5870210a))
* **ui-builder:** re-capture the remote-m3 published fixture ([#793](https://github.com/yschimke/compose-ui-builder/issues/793)) ([92fc8e8](https://github.com/yschimke/compose-ui-builder/commit/92fc8e87b3497769bb033bae8733f8f18e2a6745))
* **ui-builder:** re-capture wear-m3 after the collisions were fixed at source ([#776](https://github.com/yschimke/compose-ui-builder/issues/776)) ([bc18210](https://github.com/yschimke/compose-ui-builder/commit/bc18210d1ad1412be410815f7a136b5bdb61d787))
* **ui-builder:** re-pin wear-m3's shelf exemption, and capture its published catalog ([#767](https://github.com/yschimke/compose-ui-builder/issues/767)) ([02c560c](https://github.com/yschimke/compose-ui-builder/commit/02c560c8fc3039e98aade9f249f049f5423862f0))
* **ui-builder:** read a bound minLines without throwing out of the validator ([#266](https://github.com/yschimke/compose-ui-builder/issues/266)) ([bd363dc](https://github.com/yschimke/compose-ui-builder/commit/bd363dc1d61859bf64224641d21f731f6ef4c2dd))
* **ui-builder:** read the pane scaffold's authored widths ([#924](https://github.com/yschimke/compose-ui-builder/issues/924)) ([250bd9d](https://github.com/yschimke/compose-ui-builder/commit/250bd9d4a823e47db901f7bd1fa281c7182cdcd4))
* **ui-builder:** refuse a published catalog whose ids collide in bulk, and compare ids in the gate ([#655](https://github.com/yschimke/compose-ui-builder/issues/655)) ([b524da8](https://github.com/yschimke/compose-ui-builder/commit/b524da88cf54434a662a0750428344f02ca6d284))
* **ui-builder:** refuse an unknown property wrapper locally, not at the server ([#906](https://github.com/yschimke/compose-ui-builder/issues/906)) ([10437e8](https://github.com/yschimke/compose-ui-builder/commit/10437e8f08b32a13ebe1773970f82471f7b3c4f9))
* **ui-builder:** refuse at commit what fails at render or export ([#497](https://github.com/yschimke/compose-ui-builder/issues/497)) ([e251746](https://github.com/yschimke/compose-ui-builder/commit/e25174636d22584bd3031f65ee336f0613b1c8f7))
* **ui-builder:** refuse the component exports that would not compile ([#666](https://github.com/yschimke/compose-ui-builder/issues/666)) ([53065bc](https://github.com/yschimke/compose-ui-builder/commit/53065bc36111c4d33c5e958d6853ef5e503b416f))
* **ui-builder:** refuse what a widget brush cannot draw, and agree on the gradient axis ([#620](https://github.com/yschimke/compose-ui-builder/issues/620)) ([88dae37](https://github.com/yschimke/compose-ui-builder/commit/88dae37c83db0ef81f2015c2ca8358f9fcb0806e))
* **ui-builder:** refuse what the editor cannot author, and give a shelf its place ([#668](https://github.com/yschimke/compose-ui-builder/issues/668)) ([1bcdf5e](https://github.com/yschimke/compose-ui-builder/commit/1bcdf5e43b0f061a198b50f4c45df5913910ec4a))
* **ui-builder:** repair the render-bundle gate, and record what it found ([#814](https://github.com/yschimke/compose-ui-builder/issues/814)) ([edfa96f](https://github.com/yschimke/compose-ui-builder/commit/edfa96fbb163cae244828ea31d5efc067f85f1c9))
* **ui-builder:** replace renderer input shortcuts ([#151](https://github.com/yschimke/compose-ui-builder/issues/151)) ([4e1aff4](https://github.com/yschimke/compose-ui-builder/commit/4e1aff4e502b2f5b3b41eb2c06ee45ef21a2c1f9))
* **ui-builder:** require a slot's role and trait both, and widen the free slots to match ([#424](https://github.com/yschimke/compose-ui-builder/issues/424)) ([e4fc966](https://github.com/yschimke/compose-ui-builder/commit/e4fc966657275e967ab9f0f974f0e3985a0ae158))
* **ui-builder:** resolve outlines on the accepted document, and name icons by name ([#746](https://github.com/yschimke/compose-ui-builder/issues/746)) ([4664c91](https://github.com/yschimke/compose-ui-builder/commit/4664c91a34ca8ec9a7bce52159438acc25aa35dd))
* **ui-builder:** review wear-m3's 23 component differences, and correct a wrong note ([#773](https://github.com/yschimke/compose-ui-builder/issues/773)) ([bc41d5e](https://github.com/yschimke/compose-ui-builder/commit/bc41d5ecf4b5a581c8494c100fca28710d9890d6))
* **ui-builder:** say what is actually wrong with the two variant properties left ([#408](https://github.com/yschimke/compose-ui-builder/issues/408)) ([1159bdd](https://github.com/yschimke/compose-ui-builder/commit/1159bddc65f3ebf3cc5c9feb548b8db5b61663fc))
* **ui-builder:** say why a widget has no native preview, not that it has no @Preview ([#526](https://github.com/yschimke/compose-ui-builder/issues/526)) ([7c08a32](https://github.com/yschimke/compose-ui-builder/commit/7c08a32327f54893e207ddc5d445396702d2d456))
* **ui-builder:** select an enabled creation catalog ([#171](https://github.com/yschimke/compose-ui-builder/issues/171)) ([93a3477](https://github.com/yschimke/compose-ui-builder/commit/93a34773e58f628aba3cfa85ff7e0e18ffc9edff))
* **ui-builder:** send the page's token with every request, and build catalog paths that interpolate ([#377](https://github.com/yschimke/compose-ui-builder/issues/377)) ([85a367a](https://github.com/yschimke/compose-ui-builder/commit/85a367aa399df0ae6f3173131d5207a018b9fde1))
* **ui-builder:** serialize subscriber delivery ([#97](https://github.com/yschimke/compose-ui-builder/issues/97)) ([2c990c6](https://github.com/yschimke/compose-ui-builder/commit/2c990c64c875c84bb5e25968e61c43d450c039ee))
* **ui-builder:** serve the export's record to the browser's code pane ([#730](https://github.com/yschimke/compose-ui-builder/issues/730)) ([196fa9d](https://github.com/yschimke/compose-ui-builder/commit/196fa9df08b85f379d5796362b01f2b0e4c3ce16))
* **ui-builder:** stop a burst of edits from dropping most of itself ([#409](https://github.com/yschimke/compose-ui-builder/issues/409)) ([ae5e5ec](https://github.com/yschimke/compose-ui-builder/commit/ae5e5eca2d3fb57b29ba2c1a88f2901eb058e930))
* **ui-builder:** stop writing `transformation` on Wear components without one ([#925](https://github.com/yschimke/compose-ui-builder/issues/925)) ([7618c11](https://github.com/yschimke/compose-ui-builder/commit/7618c11097a840392ce855f5348e65f6fe946e30))
* **ui-builder:** take a design's own write action before changing its sidecars ([#606](https://github.com/yschimke/compose-ui-builder/issues/606)) ([3e38bac](https://github.com/yschimke/compose-ui-builder/commit/3e38bac84c22ca88a75ebeb8820d4cd2f53c0a92))
* **ui-builder:** the loop export's six holes, and which lane it is in ([#672](https://github.com/yschimke/compose-ui-builder/issues/672)) ([b33d2df](https://github.com/yschimke/compose-ui-builder/commit/b33d2df473862d556e11127c96e13180e34bcf23))
* **ui-builder:** the root-surface notice is not an export refusal either ([#707](https://github.com/yschimke/compose-ui-builder/issues/707)) ([79e9c95](https://github.com/yschimke/compose-ui-builder/commit/79e9c9584bab46cae40930ecd58d259068ee28ba))
* **ui-builder:** undo and redo the whole history, not one step of it ([#267](https://github.com/yschimke/compose-ui-builder/issues/267)) ([b00d2ba](https://github.com/yschimke/compose-ui-builder/commit/b00d2ba0e9673d3e2e0b70be6909325233611d34))
* **ui-builder:** undo the journal a refused commit wrote, and rename what it discards ([#611](https://github.com/yschimke/compose-ui-builder/issues/611)) ([810bc48](https://github.com/yschimke/compose-ui-builder/commit/810bc48e84cae40c0f37e5f66e07fb62f558c44e))
* **ui-builder:** version-check a published record, restore nativePreview's ABI, and fix the gate that missed it ([#693](https://github.com/yschimke/compose-ui-builder/issues/693)) ([e4ac26f](https://github.com/yschimke/compose-ui-builder/commit/e4ac26f1042bd4650810e96976000dba3819896e))
* **ui-builder:** withdraw the Remote Compose seams from Wear's borrow set ([#918](https://github.com/yschimke/compose-ui-builder/issues/918)) ([34a37be](https://github.com/yschimke/compose-ui-builder/commit/34a37be8416e2448f7c55c215aea12308d905c83))
* **ui-builder:** wrap a card with no height, and name a coloured root that does not fill the frame ([#499](https://github.com/yschimke/compose-ui-builder/issues/499)) ([bfc683e](https://github.com/yschimke/compose-ui-builder/commit/bfc683e20db76ad1d7bf09cfc5bd2ab1a57ee1c2))
* **ui-builder:** write the catalog re-pin through, so a synthesised source can be retired ([#832](https://github.com/yschimke/compose-ui-builder/issues/832)) ([8e8378b](https://github.com/yschimke/compose-ui-builder/commit/8e8378be56ae8370f13e8caa47c7bc36dcdc5809))
* **ui-builder:** write the widget vocabulary remote-m3 offers, and redirect the catalog-less design URL ([#515](https://github.com/yschimke/compose-ui-builder/issues/515)) ([fe70d38](https://github.com/yschimke/compose-ui-builder/commit/fe70d383355ea27aee08853b9740c7a547f79863))
* unbreak main's visual harness ([#812](https://github.com/yschimke/compose-ui-builder/issues/812)), re-pin stored designs ([#818](https://github.com/yschimke/compose-ui-builder/issues/818)), make the two ktfmt gates agree ([#822](https://github.com/yschimke/compose-ui-builder/issues/822)) ([#828](https://github.com/yschimke/compose-ui-builder/issues/828)) ([85aba02](https://github.com/yschimke/compose-ui-builder/commit/85aba02f2c71c91f40de4285458ebef8244a9bc1))
* unstick main from the remaining Compose 1.12.0 fallout ([#737](https://github.com/yschimke/compose-ui-builder/issues/737)) ([4e4c00b](https://github.com/yschimke/compose-ui-builder/commit/4e4c00b1c65b739da4ca676dcb000af668d82e33))


### Performance Improvements

* **serve:** keep warm Android sandbox workers for catalog daemons to adopt ([#677](https://github.com/yschimke/compose-ui-builder/issues/677)) ([2c518f7](https://github.com/yschimke/compose-ui-builder/commit/2c518f73c3073265058162fcc7436920306e62d8))


### Code Refactoring

* **ui-builder:** borrow only foundation into the wear catalog ([#389](https://github.com/yschimke/compose-ui-builder/issues/389)) ([078ca59](https://github.com/yschimke/compose-ui-builder/commit/078ca5942e3367c198bff011b59f590d9b83ff48))
