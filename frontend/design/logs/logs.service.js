angular.module('breadboard.logs.service', [])
  .factory('LogsSrv', LogsSrv);

LogsSrv.$inject = ['$http'];

function LogsSrv($http) {
  return {
    getLogs: getLogs
  };

  /**
   * One window of the server log. params: before | after (seq cursors), limit, q, level.
   * Resolves to { enabled, entries, hasMore, cursor, latestSeq }.
   */
  function getLogs(params) {
    return $http.get('logs', { params: params })
      .then(function (response) {
        return response.data;
      });
  }
}
