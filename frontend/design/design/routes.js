import loginTemplateUrl from '../templates/login.html';
import createFirstUserTemplateUrl from '../templates/create-first-user.html';
import homeTemplateUrl from '../templates/home.html';
import '../login/login.directive';
import '../create-first-user/create-first-user.directive';
import '../middleware/Authorization.middleware';
import '../services/services.module';

angular.module('breadboard.routes', ['ui.router', 'breadboard.middleware', 'ngCookies'])
  .config(['$stateProvider', '$urlRouterProvider', '$httpProvider', function($stateProvider, $urlRouterProvider, $httpProvider){
  $urlRouterProvider.otherwise('/');
  $stateProvider
    .state('login', {
      url: '/login',
      templateUrl: loginTemplateUrl,
      controller: ['$scope', '$state', '$cookies', function($scope, $state, $cookies){
        $scope.path = '/login';
        $scope.onSuccess = function(res){
          // $cookieStore was removed in angular-cookies 1.6+; $cookies.putObject/getObject
          // is the drop-in replacement (same JSON encoding, so existing cookies still decode).
          $cookies.putObject('email', res.data.email);
          $cookies.putObject('juid', res.data.juid);
          $cookies.putObject('uid', res.data.uid);
          // Load the console again, signed in. Going to 'home' in this page left it without a
          // connection: the socket opened while signed out is closed for good, and
          // Breadboard.connect() kept handing back that closed one.
          window.Breadboard.disconnect()
          setTimeout(function () {
            // the console's address without "#!/login", so the reload doesn't open the sign-in page
            // again (both in one go, so the router never sees the address in between)
            window.history.replaceState(null, '', window.location.pathname + window.location.search);
            window.location.reload();
          });
        };
      }]
    })
    .state('home', {
      url: '/',
      controller: 'AppCtrl',
      templateUrl: homeTemplateUrl,
      onExit: function(){
        // remove this hack once we're free of jquery ui dialogs
        //window.location.reload();
      }
    })
    .state('create-first-user', {
      url: '/create-first-user',
      templateUrl: createFirstUserTemplateUrl
    });

    $httpProvider.interceptors.push('AuthorizationMiddleware');

}]);
