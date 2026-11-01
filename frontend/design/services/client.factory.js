ClientFactory.$inject = ['websocketFactory', '$rootScope', 'configService'];
export default function ClientFactory($websocketFactory, $rootScope, configService){
  // Signs in as the Vue participant page does: on the first connection, and again (with how long the
  // page was out of touch) on one that follows a drop. The server only ever kept the id.
  Breadboard.login()

  return {
    async onmessage (callback) {
      await Breadboard.connect()
      Breadboard.on('message', function () {
        let args = arguments
        $rootScope.$apply(function () {
          callback.apply(null, args)
        })
      })
    },
    async send (message) {
      await Breadboard.connect()
      // (through sendType, which sends nothing on a preview that only watches its player)
      Breadboard.sendType(message.action, message)
    }
  }
}
