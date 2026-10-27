import Vue from 'vue'
import { BreadboardClass } from '../core/breadboard'
import { Emitter } from 'goodish'
import { Graph } from '../client/lib/graph'

export interface Node {
  id: string
  [key: string]: any
}

interface Edge {}

enum GraphAction {
  ADD_NODE = 'addNode',
  REMOVE_NODE = 'removeNode',
  NODE_PROP_CHANGE = 'nodePropertyChanged',
  NODE_PROP_REMOVE = 'nodePropertyRemoved'
}

interface DataMessage {
  action: GraphAction,
  [key: string]: any
}

export class AdminGraph {

  public nodes: Node[] = []
  public edges: Edge[] = []
  private breadboard!: BreadboardClass
  // Held, not extended: this file is compiled to ES5, and an ES5 class can't extend goodish's
  // native class (`new AdminGraph()` threw, and the whole console failed to load)
  private events = new Emitter()

  constructor () {
    this.handleGraphChanges = this.handleGraphChanges.bind(this)
  }

  public on (eventName: string, callback: Function) {
    this.events.on(eventName, callback)
  }

  public off (eventName: string, callback: Function) {
    this.events.off(eventName, callback)
  }

  public emit (eventName: string, ...args: any[]) {
    this.events.emit(eventName, ...args)
  }

  public attachToBreadboard (breadboard: BreadboardClass) {
    this.breadboard = breadboard
    breadboard.on('data', this.handleGraphChanges)
  }

  public releaseFromBreadboard () {
    if (this.breadboard) {
      this.breadboard.off('data', this.handleGraphChanges)
    }
  }

  private makeBlankNode (nodeId: string) {
    this.nodes.push({
      id: nodeId
    })
  }

  private removeNode (nodeId: string) {
    const index = this.nodes.findIndex(n => n.id === nodeId)
    if (index > -1) {
      this.nodes.splice(index, 1)
    }
  }

  private updateNode (nodeId: string, key: string, value: any) {
    for (const node of this.nodes) {
      if (node.id === nodeId) {
        // Vue.set/delete: a property a player gets after the view showed them still updates it
        if (value === null || value === undefined) {
          Vue.delete(node, key)
        } else {
          try {
            value = JSON.parse(value)
          } catch (err) {
          } finally {
            Vue.set(node, key, value)
          }
        }
      }
    }
  }

  private handleGraphChanges (data: DataMessage) {
    switch (data.action) {
      case GraphAction.ADD_NODE:
        this.makeBlankNode(data.id)
        break
      case GraphAction.REMOVE_NODE:
        this.removeNode(data.id)
        break
      case GraphAction.NODE_PROP_CHANGE:
      case GraphAction.NODE_PROP_REMOVE:
        this.updateNode(data.id, data.key, data.value)
        break
    }

    if ([GraphAction.ADD_NODE, GraphAction.REMOVE_NODE, GraphAction.NODE_PROP_CHANGE, GraphAction.NODE_PROP_REMOVE].includes(data.action)) {
      this.emit('nodes', this.nodes)
    }

  }

}