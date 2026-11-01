ExperimentImportCtrl.$inject = ['$scope', 'Upload', '$timeout'];

/**
 * Where an exported experiment is imported under {@code name}: one path segment, encoded. Unencoded,
 * a name with a "/", "?" or "#" in it went to another address, or none, and got a 404 instead of
 * the server's reason for refusing it.
 */
export function importUrl(path, name) {
  return path + '/' + encodeURIComponent(name);
}

export default function ExperimentImportCtrl($scope, Upload, $timeout){

  $scope.import = {
    path: '/experiment/import',
    file: null,
    name: '',
    importedName: '',
    error: '',
    success: false,
    selectExperiment: true
  };

  $scope.$watch('import.file.name', function() {
    if ($scope.import.file !== null && $scope.import.name === "") {
      let fileName = $scope.import.file.name;
      let indexOfZip = fileName.indexOf('.zip');
      fileName = fileName.substring(0, indexOfZip);
      $scope.import.name = fileName;
    }
  }, true);

  $scope.importExperiment = function(){
    if(!$scope.import.file) return;
    // (a retry: the last refusal no longer stands)
    $scope.import.error = '';
    let name = $scope.import.name.length ? $scope.import.name : $scope.import.file.name;
    name = name.replace('.zip', '');
    Upload.upload({
      url: importUrl($scope.import.path, name),
      data: {
        file: $scope.import.file
      }
    }).then(function(resp){
      if (resp.status < 400) { //Success
        $scope.import.file = null;
        $scope.import.importedName = $scope.import.name;
        $scope.import.name = '';
        $scope.import.success = true;
        if ($scope.import.selectExperiment) {
          $scope.selectExperiment()(resp.data.id);
        }
        $timeout(function() {
          $scope.import.importedName = '';
          $scope.import.success = false;
          $('#importExperimentDialog').dialog('close');
        }, 1500);
      } else {
        errorOnUpload(resp);
      }
    }, function(err){
      errorOnUpload(err)
    }, function(evt){
      console.log('import upload progress', evt);
    });
  };

  // The file and the name stay, so a refused name is all that needs changing: both used to be
  // emptied, and the file chosen again.
  function errorOnUpload(err) {
    console.error(err);
    $scope.import.error = (err.data) ? err.data : err;
  }

}