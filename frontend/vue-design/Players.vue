<template>
  <div class="players flex h-full overflow-hidden">
    <div class="flex-grow-0 h-full overflow-auto flex-shrink-0 p-1">
      <h5>Players {{numFilteredNodes}} / {{totalNodes}}</h5>
      <input type="text" v-model="expression" placeholder="Filter..." />
      <div v-for="node in nodes" :key="node.id" 
        class="player p-1" 
        :class="{'selected': (selectedNode && node.id === selectedNode.id)}"
        @click="selectNode(node.id, true)">
        {{node.id}}
      </div>
    </div>
    <div class="data p-1 h-full overflow-auto">
      <template v-if="selectedNode">
        <vue-json-pretty :data="filteredNode" :deep="1" />
        <!-- The player's screen comes from the experiment (and from what players typed), so it is
             shown in a sandboxed frame where nothing can run or reach this console. -->
        <div class="client-frame-wrap">
          <iframe
            class="client-content"
            sandbox=""
            referrerpolicy="no-referrer"
            title="The player's screen"
            :srcdoc="clientDoc" />
        </div>
        <div class="flex" v-if="selectedNode">
          <button @click="sendChoice(choice)" v-for="choice in selectedNode.choices" :key="choice.uid">{{choice.name}}</button>
        </div>
      </template>
      <div v-else>
        Select a player to view their data...
      </div>
    </div>
  </div>
</template>

<script lang="ts">
  import Vue, { PropOptions } from 'vue'
  // @ts-ignore
  import VueJsonPretty from 'vue-json-pretty'
  import { AdminGraph, Node } from './AdminGraph'
  import { PlayerChoice } from '../core/breadboard.types'

  export default Vue.extend({
    name: 'App',
    components: { VueJsonPretty },
    props: {
      // Long-lived store created and attached to Breadboard in main.ts. It is
      // shared (not owned by this component), so the view can mount/unmount
      // without dropping events or detaching the data stream.
      graph: <PropOptions<AdminGraph>>{
        type: Object,
        required: true
      },
      propBlacklist: <PropOptions<string[]>>{
        type: Array,
        default: () => ['text', 'choices']
      }
    },
    data () {
      const styleTag = document.getElementById('styleTag')
      return {
        expression: '',
        selectedNode: null as Node | null | undefined,
        // The graph's own list, through data: Vue 2.7 doesn't watch inside props, so the list
        // didn't follow players joining and leaving
        nodeList: this.graph.nodes,
        // The experiment's style, which the console page applies to itself (#styleTag); the
        // player's screen shows inside a frame now, so it gets a copy
        experimentStyle: styleTag ? styleTag.textContent || '' : '',
        styleObserver: null as MutationObserver | null
      }
    },
    created () {
      window.Breadboard.on('graph-select', this.selectNode)
      window.Breadboard.on('data', this.updateStyle)
      // The Customize dialog saves a new style straight into #styleTag, and no state frame follows
      const styleTag = document.getElementById('styleTag')
      if (styleTag && typeof MutationObserver !== 'undefined') {
        this.styleObserver = new MutationObserver(() => {
          this.experimentStyle = styleTag.textContent || ''
        })
        this.styleObserver.observe(styleTag, { childList: true, characterData: true, subtree: true })
      }
    },
    beforeDestroy () {
      window.Breadboard.off('graph-select', this.selectNode)
      window.Breadboard.off('data', this.updateStyle)
      if (this.styleObserver) this.styleObserver.disconnect()
    },
    methods: {
      selectNode (nodeId: string, emit = false) {
        console.log('select', nodeId, this.selectedNode)
        this.selectedNode = this.selectedNode && this.selectedNode.id === nodeId ? null : this.nodeList.find(n => n.id === nodeId)
        if (emit) {
          window.Breadboard.emit('player-select', this.selectedNode ? nodeId : null)
        }
      },
      // The console's state frames carry the selected experiment, style included
      updateStyle (data: any) {
        if (data && data.experiment && typeof data.experiment === 'object') {
          const style = data.experiment.style
          this.experimentStyle = typeof style === 'string' ? style : ''
        }
      },
      async sendChoice (choice: PlayerChoice) {
        const config = await window.Breadboard.loadConfig()
        window.Breadboard.sendType('MakeChoice', {
          choiceUID: choice.uid,
          uid: config.uid
        })
      }
    },
    computed: {
      nodes (): Node[] {
        const exp = this.expression
        const operators = ['=', '<', '>', '~']
        let operator = '='
        for (const op of operators) {
          if (exp.includes(op)) {
            operator = op
            break
          }
        }
        if (exp.includes(operator)) {
          const parts = exp.split(operator)
          const expected = parts[1].trim()
          const query = parts[0].split('.').map(k => k.trim())
          console.log(query, expected)
          return this.nodeList.filter(n => {
            let v: any = n
            for (let i = 0; i < query.length; i++) {
              const key = query[i]
              if (v !== null && typeof v === 'object') {
                v = v[key]
              } else {
                return false
              }
            }
            let res: boolean = false
            switch (operator) {
              case '=':
                res = v == expected
                break 
              case '<':
                res = v < expected
                break
              case '>':
                res = v > expected
                break
              case '~': {
                if (typeof v !== 'string') {
                  v = v.toString()
                }
                res = v.toLowerCase().includes(expected.toLowerCase())
                break
              }
            }
            console.log(n, v, operator, res)
            return res
          })
        } else if (exp.length) {
          return this.nodeList.filter(n => n.id && n.id.includes(exp))
        } else {
          return this.nodeList
        }
      },
      filteredNode (): Node | null {
        if (this.selectedNode) {
          const d: Node = { id: this.selectedNode.id }
          for (const key in this.selectedNode) {
            if (!this.propBlacklist.includes(key)) {
              d[key] = this.selectedNode[key]
            }
          }
          return d
        } else {
          return null
        }
      },
      // sandbox="" lets nothing in it run; the policy also keeps it from loading anything but
      // images, media, styles and fonts
      clientDoc (): string {
        const html = this.selectedNode && this.selectedNode.text ? String(this.selectedNode.text) : ''
        // a "</style" in the style would end the style element and let the rest in as markup
        const css = this.experimentStyle.replace(/<\/style/gi, '<\\/style')
        return `<!doctype html><html><head><meta charset="utf-8">
<base href="${window.location.origin}/" target="_blank">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src * data: blob:; media-src *; style-src 'unsafe-inline' *; font-src * data:">
<style>body { margin: 8px; font-family: sans-serif; } img, video { max-width: 100%; height: auto; }</style>
<style>${css}</style>
</head><body>${html}</body></html>`
      },
      totalNodes (): number {
        return this.nodeList.length
      },
      numFilteredNodes (): number {
        return this.nodes.length
      }
    }
  })
</script>

<style lang="sass" scoped>
  .overflow-auto
    overflow: auto
  .overflow-hidden
    overflow: hidden
  .h-full
    height: 100%
  .m-1
    margin: .8em
  .m-2
    margin: 1em
  .m-3
    margin: 1.2em
  .p-1
    padding: .8em
  .p-2
    padding: 1em
  .p-3
    padding: 1.2em
  .flex
    display: flex
    flex: 1 1 auto
  .flex-grow-0
    flex-grow: 0
  .flex-shrink-0
    flex-shrink: 0
  .selected
    background-color: lightgrey
  // The rest of the dialog: sized by its content, the frame below stayed at an iframe's default 300px
  .data
    flex: 1 1 auto
    min-width: 0
  .client-frame-wrap
    height: 320px
    min-height: 120px
    max-height: 75vh
    margin: .5em 0
    border: 1px solid lightgrey
    overflow: hidden
    resize: vertical
  .client-content
    display: block
    width: 100%
    height: 100%
    border: 0
</style>