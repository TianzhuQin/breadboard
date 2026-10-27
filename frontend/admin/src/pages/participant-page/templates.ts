/**
 * The pages new experiments start with, copied from conf/defaults/default-client-html.html and
 * conf/defaults/default-client-graph.js (test/participant-page.test.ts checks they still match).
 */

/** The new participant page: BBGame lays out the network, text, choices and a waiting screen. */
export const DEFAULT_CLIENT_HTML = `<v-app class="bb-app">
  <ConnectionStatus />
  <BBMain :player="player">
    <!--
      BBGame lays out the page: the network on the left, the experiment's text, timers, choices
      and forms on the right, and a waiting screen while an autopilot session fills up.

      Options for the network go in network-props, for example
        :network-props="{ nodeLabel: node => node.data.name, nodeBadge: node => node.data.score }"
      layout: 'ring' (you in the middle, the default) or 'force'.
      With g.setVisibility("egoTies") the ties among your neighbors are drawn too, and with
      edgeEdits.allow(player, ...) participants cut and add ties by clicking on the network.

      The previous default page (SVGGraph) still works: see conf/defaults/legacy-client-html.html.
    -->
    <BBGame :player="player" :graph="graph" />
  </BBMain>
</v-app>
`

/** The standard client graph: loads Breadboard's components and renders the Client HTML. */
export const DEFAULT_CLIENT_GRAPH = `async function init () {
  await Breadboard.load(window.loadVue({ useDev: true }))
  Breadboard.load((core, config) => {
    window.createDefaultVue(config.clientHtml)
  })
}

init()`
