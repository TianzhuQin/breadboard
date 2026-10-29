import './client/client.sass'
import 'core-js'
import 'regenerator-runtime'
import angular from 'angular';
import 'jquery';
import 'jquery-ui';
import 'underscore';
import 'angular-route';
import 'angular-sanitize';
import './client/client-directives';
import './client/client-filters';
import './timer/timer.directive';
import './services/services.module';
import './util/util';
import './timer/timer.directive';
import './client/client.sass';
import '../lib/angular/angular-bind-html-compile';

import _ from 'underscore';
window._ = _;

import ClientCtrl from './client/client.controller';
import ChoicesCtrl from './client/choices.controller';
const clientHtmlCacheLocation = 'holy.cow.client.html';

// One line above the page when it can't act for its player: another page has the seat (this one
// stopped), or it is a researcher's preview that only watches a player whose own page is connected.
// (Breadboard.connection, from breadboard-core)
function connectionNotice (bb) {
  if (!bb) return '';
  if (bb.connection === 'elsewhere') {
    // (a reloaded preview can't continue: it is opened again from the console)
    return bb.preview
      ? 'This player is open in another preview or tab.'
      : 'This study is open in another tab or window. Reload this page to use it here.';
  }
  if (bb.connection === 'watching') {
    return 'Watching only: this player’s own page is connected, so you see their screen but can’t answer for them.';
  }
  return '';
}

window.bbClientInit = function init () {
  angular.module('breadboard.client', [
    'breadboard.services',
    'ngSanitize',
    'ngRoute',
    'ui.bootstrap',
    'client.filters',
    'client.directives',
    'breadboard.timer',
    'angular-bind-html-compile'
  ])
  .controller('ChoicesCtrl', ChoicesCtrl)
  .directive('app', ['configService', '$templateCache', function(configService, $templateCache){
    return {
      restrict: 'E',
      replace: true,
      link: function(scope){
        scope.isLoaded = false;
        configService.get('clientHtml').then(function(html){
          scope.isLoaded = true;
          $templateCache.put(clientHtmlCacheLocation, html);
        });
        scope.connectionNotice = connectionNotice(window.Breadboard);
        function showConnection () {
          scope.$applyAsync(function () {
            scope.connectionNotice = connectionNotice(window.Breadboard);
          });
        }
        if (window.Breadboard && window.Breadboard.on) {
          window.Breadboard.on('bb:connection', showConnection);
          scope.$on('$destroy', function () {
            window.Breadboard.off('bb:connection', showConnection);
          });
        }
      },
      template: `<div class="main" ng-if="isLoaded">
                    <p class="bb-connection-notice" ng-if="connectionNotice" role="status">{{ connectionNotice }}</p>
                    <ng-include src="'${clientHtmlCacheLocation}'">
                        Loading...
                    </ng-include>
                  </div>`,
      controller: ClientCtrl,
    }
  }]);
  var app = angular.module('breadboard.client');

  app.config(['$controllerProvider', '$provide', '$compileProvider',
    function( $controllerProvider, $provide, $compileProvider ) {
      // Since the "shorthand" methods for component
      // definitions are no longer valid, we can just
      // override them to use the providers for post-
      // bootstrap loading.
      // Let's keep the older references.
      app._controller = app.controller;
      app._service = app.service;
      app._factory = app.factory;
      app._value = app.value;
      app._directive = app.directive;
      // Provider-based controller.
      app.controller = function( name, constructor ) {
        $controllerProvider.register( name, constructor );
        return( this );
      };
      // Provider-based service.
      app.service = function( name, constructor ) {
        $provide.service( name, constructor );
        return( this );
      };
      // Provider-based factory.
      app.factory = function( name, factory ) {
        $provide.factory( name, factory );
        return( this );
      };
      // Provider-based value.
      app.value = function( name, value ) {
        $provide.value( name, value );
        return( this );
      };
      // Provider-based directive.
      app.directive = function( name, factory ) {
        $compileProvider.directive( name, factory );
        return( this );
      };
      // NOTE: You can do the same thing with the "filter"
      // and the "$filterProvider"; but, I don't really use
      // custom filters.
    }]);
}
