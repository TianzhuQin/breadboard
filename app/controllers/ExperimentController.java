package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.*;

import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import play.Logger;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Security;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.apache.commons.io.FileUtils.deleteDirectory;

public class ExperimentController extends Controller {

  @Security.Authenticated(Secured.class)
  public static Result createExperiment() {
    String newExperimentName;
    Long copyExperimentId;
    String example;
    JsonNode json = request().body().asJson();
    if(json == null) {
      return badRequest("Expecting Json data");
    } else {
      newExperimentName = json.findPath("newExperimentName").textValue();
      copyExperimentId = json.findPath("copyExperimentId").asLong();
      // One of the ready-to-run examples in conf/examples, e.g. "network-game"
      example = json.findPath("example").textValue();
    }

    if (newExperimentName == null || newExperimentName.trim().isEmpty()) {
      return badRequest("Please provide a name for the new experiment.");
    }
    newExperimentName = newExperimentName.trim();
    String refused = nameRefused(newExperimentName);
    if (refused != null) {
      return badRequest(refused);
    }
    String taken = nameTaken(newExperimentName, null);
    if (taken != null) {
      return badRequest("There is already an experiment called \"" + taken + "\".");
    }

    String uid = session().get("uid");
    User user = User.findByUID(uid);

    if (user == null) {
      return badRequest("Unable to determine the user who made this request.");
    }

    Experiment experiment = null;

    Experiment copyExperiment = Experiment.findById(copyExperimentId);

    if (copyExperiment != null) {
      experiment = new Experiment(copyExperiment);
    } else if (example != null && !example.isEmpty()) {
      try {
        experiment = exampleExperiment(user, example);
      } catch (IOException e) {
        Logger.error("Unable to read the example " + example, e);
      }
      if (experiment == null) {
        return badRequest("There is no example called \"" + example + "\".");
      }
    }

    if (experiment == null) {
      experiment = newExperiment(user, true);
    }

    // (The user's default language: newExperiment adds it, a copy has the copied one's. It used to be
    // added here a second time.)
    experiment.name = newExperimentName;
    experiment.fileMode = false;
    experiment.save();

    user.ownedExperiments.add(experiment);
    user.update();
    user.saveManyToManyAssociations("ownedExperiments");

    ScriptBoard.experimentsChanged();
    return ok(experiment.toJson());
  }

  /**
   * Why an experiment can't be called {@code name}, or null when it can. The name makes folder names
   * (dev/&lt;name&gt;_&lt;id&gt;/ in file mode), where a "/" (on Windows "\" too) makes a folder in a
   * folder. Checked wherever an experiment is named: created, imported or renamed (only renaming used
   * to check).
   */
  static String nameRefused(String name) {
    // ((?s) lets "." match line breaks too: without it, a name with two of them got through)
    if (name.contains("/") || name.contains("\\") || name.matches("(?s).*\\p{Cntrl}.*")) {
      return "Names can't contain / or \\ or line breaks.";
    }
    return null;
  }

  /**
   * The name of the experiment already called {@code name} (ignoring case and outer spaces), other
   * than the one with id {@code exceptId}; null when there is none. Names are unique on a server:
   * every researcher works on every experiment, and some tools find experiments by name.
   */
  static String nameTaken(String name, Long exceptId) {
    for (Experiment other : Experiment.findAll()) {
      if ((exceptId == null || !other.id.equals(exceptId)) && other.name != null
          && other.name.trim().equalsIgnoreCase(name.trim())) {
        return other.name;
      }
    }
    return null;
  }

  /**
   * Rename one of the researcher's experiments: JSON {"name": "..."}. 200 with its JSON, or 400 with
   * what is wrong. Its sessions and data stay with it (they belong to its id). Not in file mode,
   * where the experiment's folder is named after it.
   */
  @Security.Authenticated(Secured.class)
  public static Result renameExperiment(Long experimentId) {
    User user = User.findByUID(session().get("uid"));
    Experiment experiment = Experiment.findById(experimentId);
    if (user == null || experiment == null) {
      return notFound("There is no such experiment.");
    }
    JsonNode json = request().body().asJson();
    String name = json == null ? null : json.path("name").textValue();
    name = name == null ? "" : name.trim();
    if (name.isEmpty()) {
      return badRequest("Give the experiment a name.");
    }
    if (name.length() > 120) {
      return badRequest("Keep the name under 120 characters.");
    }
    String refused = nameRefused(name);
    if (refused != null) {
      return badRequest(refused);
    }
    if (Boolean.TRUE.equals(experiment.fileMode)) {
      return badRequest("Leave file mode first: in file mode, the experiment's folder is named after it.");
    }
    String taken = nameTaken(name, experiment.id);
    if (taken != null) {
      return badRequest("There is already an experiment called \"" + taken + "\".");
    }
    experiment.name = name;
    experiment.save();
    ScriptBoard.experimentsChanged();
    return ok(experiment.toJson());
  }

  /**
   * Import an exported experiment (multipart {@code file}, a zip) under the name in the path. The zip
   * is unpacked and checked before the experiment is created: a file that isn't a zip, or has neither
   * a Steps nor a Content folder, is refused without leaving an empty experiment behind (it used to
   * create the experiment first).
   */
  @Security.Authenticated(Secured.class)
  public static Result importExperiment(String experimentName) throws IOException{
    User user = User.findByUID(session().get("uid"));
    if (user == null) {
      return badRequest("Unable to determine the user who made this request.");
    }
    // The router has already decoded the path segment ("My%20study" arrives as "My study").
    String name = experimentName == null ? "" : experimentName.trim();
    if (name.isEmpty()) {
      return badRequest("Please provide a name for the imported experiment.");
    }
    String refused = nameRefused(name);
    if (refused != null) {
      return badRequest(refused);
    }
    String taken = nameTaken(name, null);
    if (taken != null) {
      return badRequest("There is already an experiment called \"" + taken + "\".");
    }

    Http.MultipartFormData body = request().body().asMultipartFormData();
    Long maxUploadSize = play.Play.application().configuration().getLong("maxUploadSize", 50L * 1024L * 1024L);

    // Validate Content-Length header
    try {
      Long fileSize = Long.parseLong(request().getHeader("Content-Length"), 10);
      if (fileSize > maxUploadSize) {
        return badRequest("Uploaded file is too large");
      }
    } catch(Exception e){
      return badRequest("Upload was malformed");
    }

    Http.MultipartFormData.FilePart filePart = body == null ? null : body.getFile("file");
    File zippedFile = filePart == null ? null : filePart.getFile();
    if (zippedFile == null) {
      return badRequest("Must include zipFile and experiment name");
    }
    if(zippedFile.length() > maxUploadSize){
      return badRequest("Uploaded file is too large");
    }
    return importZip(user, name, zippedFile);
  }

  /**
   * Import the experiment in {@code zippedFile} as a new experiment of {@code user} named {@code name}:
   * 200 with its Experiment JSON, or 400 with what is wrong (nothing is created then).
   */
  public static Result importZip(User user, String name, File zippedFile) throws IOException {
    // Unpacked into a temporary folder of its own (it used to be experiments/ in the working directory).
    File rootOutputFolder = Files.createTempDirectory("breadboard-import").toFile();
    try {
      try {
        ZipFile zip = new ZipFile(zippedFile);
        String unsafe = unsafeEntry(zip.getFileHeaders(), rootOutputFolder);
        if (unsafe != null) {
          Logger.warn("Import: refused a zip with the entry \"" + unsafe + "\"");
          return badRequest("The zip file can't be imported: it contains \"" + unsafe + "\", which would be unpacked "
              + "outside its folder or is a link. Please upload an experiment exported from Breadboard.");
        }
        zip.extractAll(rootOutputFolder.getPath());
      } catch (ZipException e) {
        Logger.debug("Import: not a zip file: " + e.getMessage());
        return badRequest("The file is not a zip file. Please upload an experiment exported from Breadboard.");
      }
      File experimentFolder = experimentFolder(rootOutputFolder);
      if (experimentFolder == null) {
        String msg = "No Steps or Content directories found. Please upload a valid experiment";
        Logger.debug(msg);
        return badRequest(msg);
      }
      String outputFolder = experimentFolder.getPath();

      String eVersion = null;
      try {
        String dotBreadboard = readFile(outputFolder + File.separator + ".breadboard", StandardCharsets.UTF_8);
        JsonNode dotBreadboardJson = new ObjectMapper().readTree(dotBreadboard);
        eVersion = dotBreadboardJson.findPath("version").textValue();
        // TODO: offer the option to import the Experiment UID and/or Name from the .breadboard file
        Logger.debug("Read .breadboard file: experimentVersion = " + eVersion
            + " experimentUid = " + dotBreadboardJson.findPath("experimentUid").textValue()
            + " experimentName = " + dotBreadboardJson.findPath("experimentName").textValue());
      } catch (IOException | RuntimeException e) {
        Logger.debug("No readable .breadboard file present");
      }

      // Only now, with a valid upload, is the experiment created.
      Experiment experiment = newExperiment(user, false);
      experiment.name = name;
      experiment.save();
      try {
        if (eVersion != null && (eVersion.startsWith("v2.3") || eVersion.startsWith("v2.4") || eVersion.startsWith("v3."))) {
          try {
            import23To23(experiment, user, outputFolder);
          } catch (IOException e) {
            // e.g. no client-html.html: import it the older way, as before
            Logger.debug("Importing as v2.2: " + e.getMessage());
            import22To23(experiment, user, outputFolder);
          }
        } else {
          // v2.2, unknown versions and zips without a .breadboard file
          import22To23(experiment, user, outputFolder);
        }
      } catch (Exception e) {
        Logger.error("Could not import experiment " + name, e);
        try {
          experiment.delete();
        } catch (Exception cleanup) {
          Logger.error("Could not remove the half-imported experiment " + experiment.id, cleanup);
        }
        return badRequest("The experiment could not be imported: " + e.getMessage());
      }
      ScriptBoard.experimentsChanged();
      return ok(experiment.toJson());
    } finally {
      FileUtils.deleteQuietly(rootOutputFolder);
    }
  }

  /**
   * The name of the first zip entry (zip4j FileHeaders) that would not be unpacked inside
   * {@code folder} (e.g. "../../groovy/x.groovy", which zip4j 1.3.2 wrote where it points; 1.3.3
   * refuses it too, but this check doesn't depend on that), or that is a symbolic link; null when
   * every entry is safe to unpack.
   */
  static String unsafeEntry(List<?> headers, File folder) throws IOException {
    String root = folder.getCanonicalPath();
    for (Object header : headers) {
      FileHeader entry = (FileHeader) header;
      String name = entry.getFileName();
      if (name == null) {
        continue;
      }
      // An absolute name: zip4j 1.3.x puts it inside, but other versions and tools need not.
      if (name.startsWith("/") || name.startsWith("\\") || name.matches("^[A-Za-z]:.*")) {
        return name;
      }
      // Where zip4j puts it: the folder, a separator, then the name as it is.
      String target = new File(folder.getPath() + File.separator + name).getCanonicalPath();
      if (!target.equals(root) && !target.startsWith(root + File.separator)) {
        return name;
      }
      byte[] attributes = entry.getExternalFileAttr();
      if (attributes != null && attributes.length == 4) {
        // Unix file mode in the upper two bytes (little-endian); 0120000 is a symbolic link.
        int mode = ((attributes[3] & 0xff) << 8) | (attributes[2] & 0xff);
        if ((mode & 0170000) == 0120000) {
          return name;
        }
      }
    }
    return null;
  }

  /**
   * Where the unpacked experiment is: the folder itself, or the one folder it holds (zips made by
   * compressing a folder), whichever has a Steps or Content folder; null when neither does. macOS's
   * __MACOSX metadata folder is removed first.
   */
  static File experimentFolder(File unpacked) throws IOException {
    File[] files = unpacked.listFiles();
    if (files == null) {
      return null;
    }
    List<File> entries = new ArrayList<File>();
    for (File file : files) {
      if (file.isDirectory() && (file.getName().equals("__MACOSX") || file.getName().equals("__MAC_OSX"))) {
        deleteDirectory(file);
      } else {
        entries.add(file);
      }
    }
    if (hasExperimentFolders(unpacked)) {
      return unpacked;
    }
    if (entries.size() == 1 && entries.get(0).isDirectory() && hasExperimentFolders(entries.get(0))) {
      Logger.debug("Using subdirectory " + entries.get(0) + " for import instead.");
      return entries.get(0);
    }
    return null;
  }

  private static boolean hasExperimentFolders(File folder) {
    return new File(folder, "Steps").isDirectory() || new File(folder, "Content").isDirectory();
  }

  @Security.Authenticated(Secured.class)
  public static Result exportExperiment(Long experimentId){

    Experiment experiment = Experiment.findById(experimentId);
    String uid = session().get("uid");
    User user = User.findByUID(uid);
    if(experiment == null){
      return badRequest("No experiment found with that ID");
    }

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    try(ZipOutputStream zos = new ZipOutputStream(outputStream)) {
      ZipEntry e;

      ObjectNode dotBreadboard = Json.newObject();
      // The export format is still v2.4's, so older Breadboards can import it.
      dotBreadboard.put("version", "v2.4.0");
      dotBreadboard.put("experimentName", experiment.name);
      dotBreadboard.put("experimentUid", experiment.uid);

      e = new ZipEntry(".breadboard");
      zos.putNextEntry(e);
      zos.write(dotBreadboard.toString().getBytes());
      zos.closeEntry();

      // Client style/html/graph
      e = new ZipEntry("style.css");
      zos.putNextEntry(e);
      zos.write(experiment.getStyle().getBytes());
      zos.closeEntry();

      e = new ZipEntry("client-html.html");
      zos.putNextEntry(e);
      zos.write(experiment.getClientHtml().getBytes());
      zos.closeEntry();

      e = new ZipEntry("client-graph.js");
      zos.putNextEntry(e);
      zos.write(experiment.getClientGraph().getBytes());
      zos.closeEntry();

      // Steps
      for (Step step : experiment.getSteps()) {
        e = new ZipEntry("Steps/" + step.name.concat(".groovy"));
        zos.putNextEntry(e);
        zos.write(step.source.getBytes());
        zos.closeEntry();
      }

      // Content in language subfolders
      for (Content c : experiment.getContent()){
        for(Translation t : c.translations){
          String language = (t.language == null || t.language.getCode() == null) ? user.defaultLanguage.getCode(): t.language.getCode();
          e = new ZipEntry("Content/" + language + "/" + c.name.concat(".html"));
          zos.putNextEntry(e);
          zos.write(t.html.getBytes());
          zos.closeEntry();
        }
      }

      // Create the parameters.csv file
      e = new ZipEntry("parameters.csv");
      zos.putNextEntry(e);
      zos.write(experiment.parametersToCsv().getBytes());
      zos.closeEntry();

      // Write image files to stream
      for (Image image : experiment.getImages()) {
        e = new ZipEntry("Images/" + image.fileName);
        zos.putNextEntry(e);
        zos.write(image.file);
        zos.closeEntry();
      }

      // Finish by closing the stream
      zos.close();

    } catch(IOException ioe) {
      ioe.printStackTrace();
    }

    return ok(outputStream.toByteArray());

  }

  /**
   * If experimentId is null or invalid, creates a new experiment and returns the newly created experiment ID.
   * Otherwise, imports the experiment files over the existing experiment and returns the provided experimentID.
   * @param experimentId  The ID of the experiment to import over, if null or invalid create new experiment
   * @param user          The User who is making the import request, newly created experiments will be associated with the user
   * @param directory     The File location of the experiment files to impoart
   * @return              the experimentId of the imported experiment
   * @throws IOException
   */
  public static Long importExperimentFromDirectory(Long experimentId, User user, File directory) throws IOException {
    Experiment experiment;
    Logger.debug("Importing experiment " + experimentId + " from " + directory);
    if (experimentId == null || ((experiment = Experiment.findById(experimentId)) == null) ) {
      // New experiment
      experiment = new Experiment();
      user.ownedExperiments.add(experiment);
      user.update();
      user.saveManyToManyAssociations("ownedExperiments");
    } else {
      // Existing experiment, delete before re-importing
      experiment.removeSteps();
      experiment.removeContent();
      experiment.removeParameters();
      experiment.removeImages();
    }
    experiment.setClientGraph(FileUtils.readFileToString(new File(directory, "client-graph.js")));
    experiment.setClientHtml(FileUtils.readFileToString(new File(directory, "client-html.html")));
    experiment.setStyle(FileUtils.readFileToString(new File(directory, "style.css")));
    importParameters(experiment, new File(directory, "parameters.csv"));
    importSteps(experiment, new File(directory, "/Steps"));
    importContent(experiment, new File(directory, "/Content"));
    importImages(experiment, new File(directory, "/Images"));
    experiment.save();
    return experiment.id;
  }

  public static void exportExperimentToDirectory(Long experimentId, File directory) throws IOException {
    Experiment experiment = Experiment.findById(experimentId);
    if(experiment == null){
      throw new IOException("Experiment with the ID " + experimentId + " not found.");
    }

    // Clean up existing files
    if (directory.exists() && directory.isDirectory()) {
      FileUtils.deleteDirectory(directory);
    }

    // Create the directory
    FileUtils.forceMkdir(directory);

    String version = play.Play.application().configuration().getString("application.version");
    ObjectNode dotBreadboard = Json.newObject();
    dotBreadboard.put("version", version);
    dotBreadboard.put("experimentName", experiment.name);
    dotBreadboard.put("experimentUid", experiment.uid);

    FileUtils.writeStringToFile(new File(directory, ".breadboard"), dotBreadboard.toString());
    FileUtils.writeStringToFile(new File(directory, "style.css"), experiment.getStyle());
    FileUtils.writeStringToFile(new File(directory, "client-html.html"), experiment.getClientHtml());
    FileUtils.writeStringToFile(new File(directory, "client-graph.js"), experiment.getClientGraph());

    File stepsDirectory = new File(directory, "Steps");
    if (!stepsDirectory.isDirectory()) {
      stepsDirectory.mkdir();
    }
    for (Step step : experiment.getSteps()) {
      FileUtils.writeStringToFile(new File(stepsDirectory, step.name.concat(".groovy")), step.source);
    }

    File contentDirectory = new File(directory, "Content");
    if (!contentDirectory.isDirectory()) {
      contentDirectory.mkdir();
    }
    for (Content c : experiment.getContent()) {
      for (Translation t : c.translations) {
        String language = (t.language == null || t.language.getCode() == null) ? "en" : t.language.getCode();
        File translationDirectory = new File(contentDirectory, language);
        FileUtils.writeStringToFile(new File(translationDirectory, c.name.concat(".html")), t.html);
      }
    }

    FileUtils.writeStringToFile(new File(directory, "parameters.csv"), experiment.parametersToCsv());

    File imagesDirectory = new File(directory, "Images");
    if (!imagesDirectory.isDirectory()) {
      imagesDirectory.mkdir();
    }
    for (Image image : experiment.getImages()) {
      FileUtils.writeByteArrayToFile(new File(imagesDirectory, image.fileName), image.file);
    }
  }


  private static String readFile(String path, Charset encoding) throws IOException{
    byte[] encoded = Files.readAllBytes(Paths.get(path));
    return new String(encoded, encoding);
  }

  /**
   * Specific import function for import v2.2 exports into v2.3 of breadboard. There are some small differences in the
   * export format that must be dealt with as well as breaking changes to the client-html and client-graph that are
   * avoided by simply not importing those files.
   * @param experiment
   * @param user
   * @param directory
   * @return
   */
  private static Boolean import22To23(Experiment experiment, User user,  String directory) throws IOException{
    try {
      String style = FileUtils.readFileToString(new File(directory, "style.css"));
      Logger.debug("style: " + style);
      experiment.setStyle(style);
    } catch(IOException e){
      Logger.error("Unable to read style.css", e);
    }
    Logger.debug("Skipping client.html. Using default instead. Please merge any customizations by hand");
    Logger.debug("Skipping client-graph.js. Using default instead. Please merge any customizations by hand.");

    // Import content
    File contentDir = new File(directory, "/Content");
    File[] contentFiles = contentDir.listFiles();
    for (File langFileOrDir : contentFiles == null ? new File[0] : contentFiles){
      if(!langFileOrDir.isDirectory()){
        Logger.debug("Content is in root of Content directory. Attempting to import as default language.");
        try {
          importTranslations(experiment, user.defaultLanguage, contentDir);
        } catch(IOException e){
          Logger.error("Unable to import content from 'Content' directory", e);
          return false;
        } finally{
          break;
        }
      } else {
        // Content is broken out by language
        String languageIso3 = langFileOrDir.getName().equals("null") ? user.defaultLanguage.getCode() : langFileOrDir.getName();
        Language language = Language.findByIso3(languageIso3);
//        Language language = Language.findByIso3(langFileOrDir.getName());
        try{
          importTranslations(experiment, language, langFileOrDir);
        } catch(IOException e){
          Logger.error("Unable to import content from " + langFileOrDir.getName(), e);
        }
      }
    }

    // Import steps
    try {
      File stepsDirectory = new File(directory, "/Steps");
      importSteps(experiment, stepsDirectory);
    } catch(IOException e){
      Logger.error("Unable to import step", e);
      return false;
    }

    // Import parameters
    try {
      File parametersFile = new File(directory, "parameters.csv");
      importParameters(experiment, parametersFile);
    } catch(IOException e){
      Logger.error("Unable to import parameters.csv", e);
    }

    // Import images
    try {
      File imagesDirectory = new File(directory, "/Images");
      importImages(experiment, imagesDirectory);
    } catch(IOException e){
      Logger.error("Unable to import images", e);
      return false;
    }

    // Write changes to DB
    experiment.setFileMode(false);
    experiment.save();

    user.ownedExperiments.add(experiment);
    user.update();
    user.saveManyToManyAssociations("ownedExperiments");

    return true;
  }


  private static Boolean import23To23(Experiment experiment, User user, String directory) throws IOException{

    Logger.debug("importing 23 to 23");
    String style = FileUtils.readFileToString(new File(directory, "style.css"));
    experiment.setStyle(style);
    experiment.setClientGraph(FileUtils.readFileToString(new File(directory, "client-graph.js")));
    experiment.setClientHtml(FileUtils.readFileToString(new File(directory, "client-html.html")));
    experiment.setFileMode(false);

    // Import Steps
    importSteps(experiment, new File(directory, "/Steps"));
    // Import Content
    importContent(experiment, new File(directory, "/Content"));
    // Import Parameters
    importParameters(experiment, new File(directory, "parameters.csv"));
    // Import Images
    importImages(experiment, new File(directory, "/Images"));
    // Save
    experiment.save();

    user.ownedExperiments.add(experiment);
    user.update();
    user.saveManyToManyAssociations("ownedExperiments");

    return true;
  }


  private static void importContent(Experiment experiment, File contentDir) throws IOException {
    ArrayList<Content> content = getContentFromDirectory(contentDir);
    for (Content c : content) {
      for (Translation t : c.translations) {
        boolean hasExperimentLanguage = false;
        for(Language l: experiment.languages){
          if(l.id.equals(t.language.id)){
            hasExperimentLanguage = true;
          }
        }
        if(!hasExperimentLanguage){
          experiment.languages.add(t.language);
        }
      }
      experiment.content.add(c);
    }
  }

  public static ArrayList<Content> getContentFromDirectory(File contentDir) throws IOException {
    ArrayList<Content> returnContent = new ArrayList<>();
    if (!contentDir.isDirectory()) {
      return returnContent;
    }
    // Default to English if the directory isn't specified
    Language defaultLanguage = Language.findByIso3("eng");
    if (contentDir == null || !contentDir.exists()) {
      throw new IOException("Directory not found.");
    }
    for (File langFileOrDir : contentDir.listFiles()){
      if(!langFileOrDir.isDirectory() && FilenameUtils.getExtension(langFileOrDir.getName()).equalsIgnoreCase("html")) {
        Logger.debug("Content is in root of Content directory. Attempting to import as default language.");
        try {
          ArrayList<Content> rootContent = getContentFromSubdirectory(contentDir, defaultLanguage);
          returnContent.addAll(rootContent);
        } catch(IOException e){
          Logger.error("Unable to import content from 'Content' directory", e);
        } finally{
          break;
        }
      } else if (langFileOrDir.isDirectory()) {
        // Content is broken out by language
        Language language = Language.findByIso3(langFileOrDir.getName());

        // Sometimes the subdirectory is named null or something else
        if(language == null){
          language = defaultLanguage;
        }
        try{
          ArrayList<Content> languageContent = getContentFromSubdirectory(langFileOrDir, language);
          returnContent.addAll(languageContent);
        } catch(IOException e){
          Logger.error("Unable to import content from " + langFileOrDir.getName(), e);
        }
      }
    }
    return returnContent;

  }


  /**
   * Import all html files in the supplied directory as translations of the supplied language and experiment.
   * @param experiment
   * @param language
   * @param directory
   * @throws IOException
   */
  private static void importTranslations(Experiment experiment, Language language, File directory) throws IOException {

    // Check for existing experiment language and add it if it doesn't exist
    boolean hasExperimentLanguage = false;
    for(Language l: experiment.languages){
      if(l.id.equals(language.id)){
        hasExperimentLanguage = true;
      }
    }
    if(!hasExperimentLanguage){
      experiment.languages.add(language);
    }

    ArrayList<Content> importedContent = getContentFromSubdirectory(directory, language);

    for(Content content: importedContent) {
      // Check for existing content and create if it doesn't exist
      boolean contentExists = false;
      for (Content c : experiment.content) {
        if (c.name.equals(content.name)) {
          contentExists = true;
          Logger.debug("Using existing content: " + content.name + " with language " + language.name);
          break;
        }
      }

      // If content with that name doesn't already exist, import it
      if (!contentExists) {
        experiment.content.add(content);
      }
    }

  }

  private static ArrayList<Content> getContentFromSubdirectory(File directory, Language language) throws IOException {
    ArrayList<Content> returnContent = new ArrayList<>();

    for(File file: directory.listFiles()){
      if(FilenameUtils.getExtension(file.getName()).equals("html")){
        Translation translation = new Translation();
        translation.language = language;
        translation.html = FileUtils.readFileToString(file);

        String contentName = FilenameUtils.removeExtension(file.getName());
        Content content = new Content();
        content.name = contentName;
        content.translations.add(translation);

        returnContent.add(content);
      }
    }

    return returnContent;
  }

  /**
   * Import all of the images from the specified directory as members of the specified experiment.
   * @param experiment
   * @param directory
   * @throws IOException
   */
  private static void importImages(Experiment experiment, File directory) throws IOException {
    ArrayList<Image> images = getImagesFromDirectory(directory);
    experiment.images.addAll(images);
    experiment.save();
  }

  public static ArrayList<Image> getImagesFromDirectory(File directory) throws IOException {
    ArrayList<Image> returnImages = new ArrayList<>();
    File[] imageFiles = directory.listFiles();
    if (!directory.isDirectory()) {
      return returnImages;
    }
    if (imageFiles != null) {
      for (File imageFile : imageFiles) {
        String imageName = FilenameUtils.removeExtension(imageFile.getName());
        String ext = FilenameUtils.getExtension(imageFile.getName());
        if(ext.matches("(jpg|jpeg|png|bmp|gif|svg|webp)")) {
          byte[] imageBytes = FileUtils.readFileToByteArray(imageFile);
          Image image = new Image();
          image.fileName = imageFile.getName();
          image.file = imageBytes;
          String extension = FilenameUtils.getExtension(imageFile.getName());
          image.contentType = "image/" + extension;
          if(extension.equals("svg")){
            image.contentType += "+xml";
          }
          returnImages.add(image);
          Logger.debug("Adding image: " + imageName);
        } else {
          Logger.debug("Skipping file of unsupported type: " + imageName);
        }
      }
    }
    return returnImages;
  }

  private static void importParameters(Experiment experiment, File file) throws IOException {
    ArrayList<Parameter> parameters = getParametersFromFile(file);
    experiment.parameters.addAll(parameters);
    experiment.update();
  }

  public static ArrayList<Parameter> getParametersFromFile(File parameterFile) throws IOException {
    // try-with-resources so the underlying file handle is always released (an unclosed
    // FileReader here leaked a handle and, on Windows, left parameters.csv locked).
    try (Reader in = new FileReader(parameterFile)) {
      return getParameters(in);
    }
  }

  private static ArrayList<Parameter> getParameters(Reader in) throws IOException {
    ArrayList<Parameter> returnParameters = new ArrayList<>();
    CSVFormat format = CSVFormat.DEFAULT.withHeader("Name", "Type", "Min.", "Max.", "Default", "Short Description").withFirstRecordAsHeader();
    for (CSVRecord record : format.parse(in)) {
      Parameter parameter = new Parameter();
      parameter.name = record.get("Name");
      parameter.type = record.get("Type");
      parameter.minVal = record.get("Min.");
      parameter.maxVal = record.get("Max.");
      parameter.defaultVal = record.get("Default");
      parameter.description = record.get("Short Description");
      returnParameters.add(parameter);
    }
    return returnParameters;
  }

  // Reusable code for importing steps
  private static void importSteps(Experiment experiment, File stepsDirectory) throws IOException{
    ArrayList<Step> steps = getStepsFromDirectory(stepsDirectory);
    for (Step step : steps) {
      experiment.addStep(step);
    }
  }

  public static ArrayList<Step> getStepsFromDirectory(File stepsDirectory) throws IOException {
    ArrayList<Step> returnSteps = new ArrayList<>();
    File[] stepFiles = stepsDirectory.listFiles();
    if (stepFiles != null) {
      for (File stepFile : stepFiles) {
        if(FilenameUtils.getExtension(stepFile.getName()).equals("groovy")) {
          Step step = new Step();
          String stepName = FilenameUtils.removeExtension(stepFile.getName());
          String source = FileUtils.readFileToString(stepFile);
          step.name = stepName;
          step.source = source;
          returnSteps.add(step);
          Logger.debug("Adding step: " + stepName);
        } else {
          Logger.debug("Skipping " + stepFile.getName() + " with unsupported file extension");
        }
      }
    }
    return returnSteps;
  }

  /**
   * A new experiment made from one of the ready-to-run examples in conf/examples/{name}/ (on the
   * classpath as examples/{name}/): example.json lists its steps in order ("steps"), which are in
   * Steps/{step}.groovy, and parameters.csv holds its launch parameters, as in an export. Examples
   * get the current participant page. Null when there is no such example.
   */
  public static Experiment exampleExperiment(User user, String name) throws IOException {
    if (!name.matches("[a-z0-9-]+")) {
      return null;
    }
    String root = "examples/" + name + "/";
    String manifest = resourceText(root + "example.json");
    if (manifest == null) {
      return null;
    }
    Experiment experiment = new Experiment();
    experiment.fileMode = false;
    for (JsonNode stepName : Json.parse(manifest).path("steps")) {
      String source = resourceText(root + "Steps/" + stepName.asText() + ".groovy");
      if (source == null) {
        throw new IOException("The example " + name + " has no step " + stepName.asText());
      }
      Step step = new Step();
      step.name = stepName.asText();
      step.source = source;
      experiment.addStep(step);
    }
    String parameters = resourceText(root + "parameters.csv");
    if (parameters != null) {
      experiment.parameters.addAll(getParameters(new StringReader(parameters)));
    }
    // What participants read, on the Content page (in the account's language) so it can be reworded
    // or translated without changing the steps.
    for (JsonNode contentName : Json.parse(manifest).path("content")) {
      String html = resourceText(root + "Content/" + contentName.asText() + ".html");
      if (html == null) {
        throw new IOException("The example " + name + " has no content " + contentName.asText());
      }
      Translation translation = new Translation();
      translation.language = user.defaultLanguage;
      translation.setHtml(html.trim());
      Content content = new Content();
      content.name = contentName.asText();
      content.translations.add(translation);
      experiment.content.add(content);
    }
    experiment.setClientHtml(Experiment.defaultClientHTML());
    experiment.setClientGraph(Experiment.defaultClientGraph());
    experiment.addLanguage(user.defaultLanguage);
    return experiment;
  }

  private static String resourceText(String path) throws IOException {
    try (InputStream in = play.Play.application().resourceAsStream(path)) {
      return in == null ? null : org.apache.commons.io.IOUtils.toString(in, StandardCharsets.UTF_8);
    }
  }

  private static Experiment newExperiment(User user, Boolean isNewExperiment){
    Experiment experiment = new Experiment();
    if (isNewExperiment) {
      Step onJoin = Experiment.generateOnJoinStep();
      Step onLeave = Experiment.generateOnLeaveStep();
      Step init = Experiment.generateInitStep();
      experiment.addStep(onJoin);
      experiment.addStep(onLeave);
      experiment.addStep(init);
    }
    // An import is an older experiment: unless it brings its own client HTML (v2.3 and later
    // exports do), it gets the client HTML imports always got, not the newer participant page.
    experiment.setClientHtml(isNewExperiment ? Experiment.defaultClientHTML() : Experiment.legacyClientHTML());
    experiment.setClientGraph(Experiment.defaultClientGraph());
    // Add the user's default language
    experiment.addLanguage(user.defaultLanguage);
    return experiment;
  }
}
