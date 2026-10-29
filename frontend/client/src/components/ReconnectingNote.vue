<template>
  <!-- Shown by the choices and forms while the connection is down, which they wait for -->
  <p class="bb-reconnecting" role="status">
    <slot>Reconnecting… you can answer once the connection is back.</slot>
  </p>
</template>

<script lang="ts">
  import Vue from 'vue'

  /**
   * A short note next to controls that wait for the connection to come back
   */
  export default Vue.extend({
    name: 'ReconnectingNote'
  })
</script>

<style>
  .bb-reconnecting {
    margin: 0 0 12px;
    font-size: 14px;
    line-height: 1.45;
    color: #64748b;
  }
  .bb-reconnecting::before {
    content: '';
    display: inline-block;
    width: 8px;
    height: 8px;
    margin-right: 8px;
    border-radius: 50%;
    background: #f59e0b;
    vertical-align: 1px;
  }
</style>
