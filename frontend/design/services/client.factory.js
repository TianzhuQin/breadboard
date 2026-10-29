ClientFactory.$inject = ['websocketFactory', '$rootScope', 'configService'];
export default function ClientFactory($websocketFactory, $rootScope, configService){
  Breadboard.connect().then(async () => {
    const clientVars = await configService.all()
    Breadboard.sendType('LogIn', clientVars)
  })

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
