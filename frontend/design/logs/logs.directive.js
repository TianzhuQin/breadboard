import templateUrl from './logs.template.html';
import './logs.app';
import LogsCtrl from './logs.controller';
angular.module('breadboard.logs').directive('logs', function() {
  return {
    restrict: 'E',
    scope: {},
    controllerAs: 'vm',
    controller: LogsCtrl,
    templateUrl: templateUrl
  }
});
