package controllers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import models.Experiment;
import models.Image;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import play.Logger;
import play.Play;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Security;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

public class ImagesController extends Controller {

  /**
   * Add an image to an experiment: multipart {@code picture[file]} and {@code experimentId}. Answers
   * {"id", "fileName"} (the name gets "-2", "-3"... when the experiment has one by that name).
   * A failed upload is a 400 with {"message": "<why>"}; it used to be a 200 with the plain text
   * "Error uploading", which callers could mistake for the new image.
   */
  @Security.Authenticated(Secured.class)
  public static Result uploadImage() {
    Http.MultipartFormData body = request().body().asMultipartFormData();
    if (body == null) {
      return uploadFailed("Send the image as a multipart form upload (picture[file] and experimentId).");
    }
    Http.MultipartFormData.FilePart picture = body.getFile("picture[file]");
    if (picture == null || picture.getFile() == null) {
      return uploadFailed("No image file was received (picture[file]).");
    }
    Map<String, String[]> values = body.asFormUrlEncoded();
    String[] experimentIds = values == null ? null : values.get("experimentId");
    if (experimentIds == null || experimentIds.length == 0 || experimentIds[0] == null) {
      return uploadFailed("Say which experiment the image is for (experimentId).");
    }
    return storeImage(experimentIds[0], picture.getFilename(), picture.getContentType(), picture.getFile());
  }

  /**
   * Store an uploaded image file for the experiment with id {@code experimentIdText}: 200 with
   * {"id", "fileName"}, or 400 with {"message"}.
   */
  public static Result storeImage(String experimentIdText, String fileName, String contentType, File file) {
    Long eid;
    try {
      eid = Long.parseLong(experimentIdText.trim());
    } catch (NumberFormatException nfe) {
      return uploadFailed("\"" + experimentIdText + "\" is not an experiment id.");
    }
    Experiment experiment = Experiment.findById(eid);
    if (experiment == null) {
      return uploadFailed("There is no experiment with id " + eid + ".");
    }
    if (file == null) {
      return uploadFailed("No image file was received (picture[file]).");
    }
    if (fileName == null || fileName.trim().isEmpty()) {
      return uploadFailed("The image file has no name.");
    }
    fileName = FilenameUtils.getName(fileName.trim());

    try {
      Image image = new Image();
      String uniqueFileName = fileName;
      // If there is already an Image with the same filename, append a "-2" to the filename
      int appendNumber = 2;
      while (Image.find.where().eq("experiment_id", eid).eq("file_name", uniqueFileName).findRowCount() > 0) {
        uniqueFileName = FilenameUtils.removeExtension(fileName) + "-" + appendNumber + "." + FilenameUtils.getExtension(fileName);
        appendNumber++;
      }
      image.fileName = uniqueFileName;
      image.file = FileUtils.readFileToByteArray(file);
      image.contentType = contentType;
      if (image.contentType == null || image.contentType.trim().isEmpty()) {
        // Without one the image could never be served (getImage needs it); guess it as the import does.
        String extension = FilenameUtils.getExtension(fileName).toLowerCase();
        image.contentType = "image/" + ("svg".equals(extension) ? "svg+xml" : "jpg".equals(extension) ? "jpeg" : extension);
      }
      experiment.images.add(image);
      experiment.save();
      return ok(image.toJson());
    } catch (IOException ioe) {
      Logger.error("Could not read an uploaded image: " + ioe.getMessage());
      return uploadFailed("The uploaded file could not be read. Try again.");
    }
  }

  private static Result uploadFailed(String message) {
    ObjectNode json = Json.newObject();
    json.put("message", message);
    return badRequest(json);
  }

  @Security.Authenticated(Secured.class)
  public static Result removeImage(Long imageId) {
    Image image = Image.find.byId(imageId);
    if (image != null) {
      image.delete();
    } else {
      return badRequest("Invalid image ID");
    }
    return ok();
  }

  public static Result getImage(Long imageId) {
    Image image = Image.findById(imageId);

    if (image != null) {
      if (image.contentType != null && image.file != null) {
        response().setContentType(image.contentType);
        return ok(image.file);
      }
    }

    return notFound();
  }

  public static Result getImageByFileName(Long experimentId, String fileName) {
    Experiment experiment = Experiment.findById(experimentId);
    if (experiment == null) {
      return notFound();
    }

    if (experiment.fileMode) {
      File imageDirectory = new File(Play.application().path().toString() + "/dev/" + experiment.getDirectoryName() + "/Images");
      File file = imageFileIn(imageDirectory, fileName);
      if (file == null) {
        return notFound();
      }
      try {
        String contentType = Files.probeContentType(file.toPath());
        byte[] contents = FileUtils.readFileToByteArray(file);
        response().setContentType(contentType == null ? "application/octet-stream" : contentType);
        response().setHeader("X-Content-Type-Options", "nosniff");
        return ok(contents);
      } catch (IOException ioe) {
        return notFound();
      }
    }

    Image image = Image.find.where()
        .eq("experiment_id", experimentId)
        .eq("file_name", fileName)
        .setMaxRows(1)
        .findUnique();

    if (image != null) {
      if (image.contentType != null && image.file != null) {
        response().setContentType(image.contentType);
        return ok(image.file);
      }
    }

    return notFound();
  }

  /**
   * The file named {@code fileName} directly inside {@code imageDirectory}, or null when there is no
   * such regular file or the name reaches outside the folder. The image route is public and Play
   * decodes %2F in the name, so "..%2F..%2Fconf%2Fapplication.conf" used to read any file on the
   * server. A name is one path segment; symbolic links are not followed.
   */
  static File imageFileIn(File imageDirectory, String fileName) {
    if (fileName == null || fileName.isEmpty() || fileName.equals(".") || fileName.equals("..")
        || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0 || fileName.indexOf('\0') >= 0) {
      return null;
    }
    try {
      Path base = imageDirectory.toPath().toRealPath();
      Path path = base.resolve(fileName).normalize();
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().startsWith(base)) {
        return null;
      }
      return path.toFile();
    } catch (IOException | InvalidPathException e) {
      return null;
    }
  }

  public static Result getImageThumb(Long imageId) {
    Image image = Image.findById(imageId);

    if (image != null) {
      if (image.contentType != null && image.thumbFile != null && image.thumbFile.length > 0) {
        response().setContentType(image.contentType);
        return ok(image.thumbFile);
      }
    }

    return notFound();
  }
}
