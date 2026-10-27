package models;

import akka.actor.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Graph;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.gremlin.groovy.GremlinGroovyPipeline;
import groovy.util.ObservableMap;
import groovy.lang.Closure;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.groovy.control.CompilationFailedException;
import play.Logger;
import play.Play;
import play.libs.F.Callback;
import play.libs.F.Callback0;
import play.libs.Json;
import play.mvc.WebSocket;

import javax.script.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class ScriptBoard extends UntypedActor {
  private static ObjectMapper mapper = new ObjectMapper();

  private static ScriptEngineManager manager = new ScriptEngineManager();
  private static ScriptEngine engine;

  private static Map results = new HashMap();
  private static PlayerActionsInterface playerActions;
  private static BreadboardGraphInterface graphInterface;
  private static BreadboardGraphChangedListener graphChangedListener;
  private static FileWatcher fileWatcher;
  private static EventTracker eventTracker = new EventTracker();
  private static EventBus<Map> eventBus = new EventBus();

  private static Random rand = new Random();
  private static final Map<String, Client> clients = new java.util.concurrent.ConcurrentHashMap<>();
  // Final screens of the participants of the last few sessions, by instance id and client id, for
  // pages that reconnect after their session has ended (see addClient).
  private static final int ENDED_SESSIONS_KEPT = 10;
  private static final Map<Long, Map<String, ObjectNode>> endedSessionScreens = Collections.synchronizedMap(
      new LinkedHashMap<Long, Map<String, ObjectNode>>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Map<String, ObjectNode>> eldest) {
          return size() > ENDED_SESSIONS_KEPT;
        }
      });

  // AMT lifecycle timers scheduled by the HitCreated handler. Tracked so they can be
  // cancelled on reload / on re-submit instead of being orphaned (MEMORY_LEAKS.md L6).
  // CopyOnWriteArrayList because the static state is shared across ScriptBoard actors.
  static final List<Timer> amtTimers = new CopyOnWriteArrayList<Timer>();

  // A list of admins currently watching the game.
  private static ArrayList<Admin> admins = new ArrayList<>();
  private static UserDataInterface instanceData;
  private GameListener gameListener = new GameListener();

  private Long experimentId;
  private Long instanceId;

  public static boolean checkPassword(String _password) {
    // If no password is set, always return true
    Object password = currentPassword();
    if (password == null)
      return true;
    return password.toString().equals(_password);
  }

  /** Whether the experiment set a participant password (the login form only asks for one then). */
  public static boolean hasPassword() {
    return currentPassword() != null;
  }

  // The experiment's "password" binding, or null when there is none or it is blank. Read once from
  // a local copy of the engine, which a reload can replace at any moment.
  private static Object currentPassword() {
    ScriptEngine current = engine;
    if (current == null)
      return null;
    Object password = current.get("password");
    if (password == null || password.toString().trim().isEmpty())
      return null;
    return password;
  }

  public ScriptBoard() throws IOException, ScriptException {
    rebuildScriptBoard(null);
  }

  private void init() {
    results = new HashMap();
    mapper = new ObjectMapper();
    admins = new ArrayList<>();
    rememberLastScreens(clients.values());
    clients.clear();
    eventTracker = new EventTracker();
    manager = new ScriptEngineManager();
    eventBus.clear();
  }

  private void disconnectClients () {
    // Disconnect all connected clients
    for (Client client : clients.values()) {
      client.disconnect();
      // Also drop the client from the graph-change dispatch registry. Otherwise the
      // listener keeps a reference to every client that ever connected (a leak) and
      // keeps dispatching graph updates to its now-closed socket until a same-id
      // client happens to reconnect and overwrite the entry.
      if (graphChangedListener != null) {
        graphChangedListener.removeClientListener(client);
      }
    }
    rememberLastScreens(clients.values());
    clients.clear();
  }

  static void rememberLastScreens(Collection<Client> sessionClients) {
    for (Client client : sessionClients) {
      if (client.lastFrame == null || client.experimentInstance == null || client.experimentInstance.id == null) {
        continue;
      }
      Long instanceId = client.experimentInstance.id;
      synchronized (endedSessionScreens) {
        Map<String, ObjectNode> screens = endedSessionScreens.get(instanceId);
        if (screens == null) {
          screens = new java.util.concurrent.ConcurrentHashMap<>();
          endedSessionScreens.put(instanceId, screens);
        }
        screens.put(client.id, client.lastFrame);
      }
    }
  }

  /** What a page that reconnects to a session that has ended is sent: its last screen, if known. */
  static ObjectNode endedSessionScreen(Long instanceId, String clientId) {
    Map<String, ObjectNode> screens = endedSessionScreens.get(instanceId);
    ObjectNode last = screens == null ? null : screens.get(clientId);
    if (last != null) {
      return last;
    }
    ObjectNode frame = Json.newObject();
    ObjectNode player = frame.putObject("player");
    player.putObject("_system");
    player.put("text", "<h3>This session has ended</h3><p>Thank you for taking part. You can close this page.</p>");
    return frame;
  }

  /** Cancel and forget every tracked AMT lifecycle timer. Safe to call when none exist. */
  static void cancelAmtTimers() {
    for (Timer t : amtTimers) {
      t.cancel();
    }
    amtTimers.clear();
  }

  /**
   * Schedule the two AMT lifecycle timers, first cancelling any still pending from an
   * earlier HIT submission so they are never orphaned. Extracted from the HitCreated
   * handler so the scheduling/cancellation contract is unit-testable without the actor
   * or a live script engine (MEMORY_LEAKS.md L6).
   *
   * @param noNewConnectionsDelayMs delay after which no new client connections are allowed
   * @param startInitStepDelayMs    delay after which {@code initStep.start()} is sent
   * @param onNoNewConnections      action run by the first timer
   * @param onStartInitStep         action run by the second timer
   */
  static void scheduleAmtTimers(long noNewConnectionsDelayMs, long startInitStepDelayMs,
                                final Runnable onNoNewConnections, final Runnable onStartInitStep) {
    // Replace any timers still pending from an earlier submission.
    cancelAmtTimers();

    Timer noNewConnectionsTimer = new Timer();
    noNewConnectionsTimer.schedule(new TimerTask() {
      @Override
      public void run() {
        onNoNewConnections.run();
      }
    }, noNewConnectionsDelayMs);
    amtTimers.add(noNewConnectionsTimer);

    Timer startInitStepTimer = new Timer();
    startInitStepTimer.schedule(new TimerTask() {
      @Override
      public void run() {
        onStartInitStep.run();
      }
    }, startInitStepDelayMs);
    amtTimers.add(startInitStepTimer);
  }

  private void resetEngine(Experiment experiment) throws IOException, ScriptException {
    // When started, send a message to each Admin
    for (Admin admin : admins) {
      ObjectNode jsonOutput = Json.newObject();
      ObjectNode notify = Json.newObject();
      notify.put("ScriptEngineReloading", new Date().getTime());
      jsonOutput.put("notify", notify);
      admin.getOut().write(jsonOutput);
    }
    Logger.debug("ScriptEngine reload start");
    ClientView.reset();
    Recruitment.reset();
    if (engine != null) {
      // just in case
      playerActions.turnAIOff();
      // clean up the graph
      processScript("g.empty()", null, null);
      // Reset the timers
      processScript("timers.cancel()", null, null);
      // Cancel any pending AMT lifecycle timers from a previous session so they don't
      // fire against the rebuilt engine or outlive it (MEMORY_LEAKS.md L6).
      cancelAmtTimers();
    }

    // Global events used to communicate via the groovy scripting
    eventBus.clear();
    eventBus.on("__send-event", new Closure(null) {
      public void doCall (String clientId, String eventName, Object ...data) {
        Logger.debug("client send " + clientId);
        Client client = clients.get(clientId);
        if (client == null) {
          Logger.error("Client with id, " + clientId + " has not connected yet");
          return;
        }
        client.send(eventName, data);
      }
    });
    eventBus.on("__broadcast-event", new Closure(null) {
      public void doCall (String clientId, String eventName, Object ...data) {
        Logger.debug("client broadcast " + clientId);
        for (Client client : clients.values()) {
          client.send(eventName, data);
        }
      }
    });

    engine = manager.getEngineByName("gremlin-groovy");
    engine.getBindings(ScriptContext.ENGINE_SCOPE).put("r", rand);
    engine.getBindings(ScriptContext.ENGINE_SCOPE).put("results", results);

    engine.getBindings(ScriptContext.ENGINE_SCOPE).put("eventTracker", eventTracker);
    engine.getBindings(ScriptContext.ENGINE_SCOPE).put("gameListener", gameListener);
    engine.getBindings(ScriptContext.ENGINE_SCOPE).put("events", eventBus);

    if (experiment != null) {
      engine.getBindings(ScriptContext.ENGINE_SCOPE).put("c", experiment.contentFetcher);
    }

    // Load the Groovy DSL scripts. ScriptLoader is the single source of truth for which
    // files load and in what order: the core scripts first, in dependency order, then every
    // other *.groovy file alphabetically, skipping *_test.groovy. Dropping a new script into
    // the groovy directory is enough to have it loaded -- no edit here required. A failure in
    // a non-core script is logged (naming the file) and skipped rather than aborting the boot.
    // Experimental scripts (ScriptLoader.EXPERIMENTAL_SCRIPTS) load only when the
    // breadboard.experimental flag is on, defaulting to off.
    boolean experimental = Play.application().configuration().getBoolean("breadboard.experimental", false);
    File groovyDir = new File(Play.application().path().toString(), "groovy");
    ScriptLoader.loadAll(engine, groovyDir, experimental);

    // get script object on which we want to implement the interface with
    Object a = engine.get("a");
    Invocable inv = (Invocable) engine;
    playerActions = inv.getInterface(a, PlayerActionsInterface.class);

    Object d = engine.get("d");
    instanceData = inv.getInterface(d, UserDataInterface.class);

    Object g = engine.get("g");
    graphInterface = inv.getInterface(g, BreadboardGraphInterface.class);

    if (graphChangedListener == null) {
      Logger.debug("graphChangedListener == null");
      Long clientUpdateRate = play.Play.application().configuration().getMilliseconds("breadboard.clientUpdateRate");
      if (clientUpdateRate == null || clientUpdateRate == 0) {
        Logger.debug("clientUpdateRate not found or 0, using event based updating");
        graphChangedListener = new EventGraphChangedListener((Graph) g);

      } else {
        Logger.debug("clientUpdateRate found, using polling");
        graphChangedListener = new IteratedBreadboardGraphChangedListener((Graph) g);
      }
    } else {
      Logger.debug("graphChangedListener != null");
      graphChangedListener.setGraph((Graph) g);
    }

    graphInterface.addListener(graphChangedListener);

    if (fileWatcher == null) {
      fileWatcher = new FileWatcher(admins);
    }

    gameListener.engine = engine;

    // When done, send a message to each Admin
    for (Admin admin : admins) {
      ObjectNode jsonOutput = Json.newObject();
      ObjectNode notify = Json.newObject();
      notify.put("ScriptEngineReloaded", new Date().getTime());
      jsonOutput.put("notify", notify);
      admin.getOut().write(jsonOutput);
    }
    this.disconnectClients();
    Logger.debug("ScriptEngine reload complete");
  }

  private void loadSteps(Experiment experiment, ThrottledWebSocketOut out) {
    for (Step step : experiment.getSteps()) {
      //should call RunStep?
      processScript(step.source + ";null;", out, step.name);
    }
  }

  private String makeUniqueClientId (String clientId) {
    return ScriptBoardSupport.makeUniqueClientId(this.experimentId, this.instanceId, clientId);
  }

  private void rebuildScriptBoard(Experiment experiment) throws IOException, ScriptException {
    //init();
    resetEngine(experiment);
  }

  public static void addAdmin(Admin admin) {
    admins.add(admin);
    graphChangedListener.addAdminListener(admin);
  }

  public static void addClient(String experimentIdString, String experimentInstanceIdString, String clientId, final WebSocket.In<JsonNode> in, final ThrottledWebSocketOut out) throws Exception {
    try {
      Long experimentId = Long.parseLong(experimentIdString);
      Long experimentInstanceId = Long.parseLong(experimentInstanceIdString);

      Experiment experiment = Experiment.findById(experimentId);
      if (experiment == null) {
        Logger.debug("addClient: experiment == null");
        return;
      }

      ExperimentInstance experimentInstance = null;
      if (experimentInstanceId.equals(Experiment.TEST_INSTANCE_ID)) {
        experimentInstance = experiment.getTestInstance();
      } else {
        experimentInstance = ExperimentInstance.findById(experimentInstanceId);
      }
      if (experimentInstance == null) {
        Logger.debug("addClient: experimentInstance == null");
        return;
      }

      Client client = clients.get(clientId);

      if (client == null && experimentInstance.status != null && !experimentInstance.status.isRunnable()) {
        // The session is over (finished, or given up by the autopilot), and the engine may already
        // be running the next one, which this participant must not be pulled into. Give the page
        // back its last screen (a completion code, a "could not start" notice) instead.
        Logger.debug("addClient: instance " + experimentInstanceId + " is no longer running");
        out.write(endedSessionScreen(experimentInstance.id, clientId));
        return;
      }

      if (client == null && experimentInstance.hasStarted) {
        Logger.debug("New client trying to join after game has already started.");
        return;
      }

      final Client thisClient;
      if (client == null) {
        // New client, let's create a new Client object
        client = new Client(clientId, experimentInstance, in, out);
        clients.put(clientId, client);
      } else {
        // Reconnecting (the controller already checked this request holds the seat's token):
        // switch to the new socket and close the previous one, so an older tab can neither keep
        // receiving updates nor keep sending choices for this participant.
        ThrottledWebSocketOut previous = client.out;
        client.setIn(in);
        client.setOut(out);
        if (previous != null && previous != out) {
          try {
            previous.close();
          } catch (Exception ignored) {}
        }
      }
      thisClient = client;

      in.onClose(new Callback0() {
        public void invoke() {
          // Only the current socket counts; a replaced one closing is not a disconnect.
          if (thisClient.in == in) {
            setConnected(clientId, false);
          }
        }
      });

      in.onMessage(new Callback<JsonNode>() {
        public void invoke(JsonNode event) {
          if (thisClient.in != in) {
            return; // message from a socket that has since been replaced
          }
          try {
            ObjectMapper mapper = new ObjectMapper();
            // TODO: is event.toString() the correct method here?
            Map<String, Object> jsonInput = mapper.readValue(event.toString(), Map.class);
            Object actionValue = jsonInput.get("action");
            String action = actionValue == null ? "" : actionValue.toString();
            if (action.equals("LogIn")) {
              // The browser used to report its own IP address, user agent, referer (full URL,
              // including recruitment ids) and other headers here, which were stored with every
              // login. They were unverified and mostly personal data, so only the id is kept.
              if (eventTracker != null) {
                LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
                data.put("clientId", clientId);
                eventTracker.track("clientLogIn", data);
              }
            } else if (action.equals("MakeChoice")) {
              Object uidValue = jsonInput.get("choiceUID");
              String choiceUID = uidValue == null ? null : uidValue.toString();
              String params = (jsonInput.containsKey("params") && jsonInput.get("params") != null) ? jsonInput.get("params").toString() : null;
              makeChoice(clientId, choiceUID, params, out);
            } else if (action.equals("CustomEvent")) {
              // TODO: Is is possible for this to be emitted before the event has been registered? 
              //       I think it is and I think it will cause exceptions to be thrown...
              eventBus.emit("CustomEvent", jsonInput, new HashMap<String, String>() {{
                put("clientId", clientId);
            }});
            }
          } catch (java.io.IOException ignored) {
            Logger.debug("java.io.IOException");
          } catch (Exception e) {
            ScriptLoader.humanizeStackTrace(e);
            Logger.error("Error handling client message", e);
          }
        }
      });

      if (graphChangedListener != null) {
        graphChangedListener.addClientListener(client);
      }

      if (graphInterface != null) {
        if (!experimentInstance.hasStarted) {
          graphInterface.addPlayer(clientId);
        }
        Graph wholeGraph = (Graph) engine.get("g");
        Vertex clientVertex = wholeGraph.getVertex(clientId);
        Recruitment.applyTo(clientVertex);
        // After addPlayer, so a new participant's vertex exists to record it on.
        setConnected(clientId, true);
        if (clientVertex != null) {
          if (graphChangedListener instanceof EventGraphChangedListener) {
            client.updateGraph(clientVertex);
          }
        }
      }

      // Update the client's state
      ObjectNode jsonOutput = Json.newObject();
      jsonOutput.put("style", experimentInstance.experiment.getStyle());
      Logger.debug("addClient, " + clientId);
      out.write(jsonOutput);
    } catch (NumberFormatException nfe) {
      Logger.debug("addClient: NumberFormatException");
    }
  }

  public void onReceive(Object message) throws Exception {
    try {
      if (message instanceof Breadboard.BreadboardMessage) {
        Breadboard.BreadboardMessage breadboardMessage = (Breadboard.BreadboardMessage) message;
        if (breadboardMessage.user != null) {
          gameListener.user = breadboardMessage.user;
          gameListener.out = breadboardMessage.out;
        }
        if (message instanceof Breadboard.AddAdmin) {
          Breadboard.AddAdmin addAdmin = (Breadboard.AddAdmin) message;

          addAdmin(new Admin(addAdmin.user, addAdmin.scriptBoardController, addAdmin.out));
        } else if (message instanceof Breadboard.RunGame) {
          if (breadboardMessage.user != null) {
            // Run Game
            processScript("initStep.start()", breadboardMessage.out, null);
          }

        } else if (message instanceof Breadboard.HitCreated) {
          Breadboard.HitCreated hitCreated = (Breadboard.HitCreated) message;
          if (breadboardMessage.user != null) {
            Integer lifetimeInMs = hitCreated.lifetimeInSeconds * 1000;
            Integer tutorialTimeInMs = hitCreated.tutorialTime * 1000;
            Long timerTime = System.currentTimeMillis() + (lifetimeInMs + tutorialTimeInMs);
            // startAt will be used to add timer to clients for start of game
            processScript("startAt = " + timerTime, breadboardMessage.out, null);

            final ThrottledWebSocketOut breadboardOut = breadboardMessage.out;
            // Schedule the two AMT lifecycle timers via the tracked registry so they are
            // cancelled on reload / superseded on re-submit instead of leaking. Same delays
            // and actions as before: at lifetimeInMs no new connections are allowed; at
            // lifetimeInMs + tutorialTimeInMs initStep.start() is sent (MEMORY_LEAKS.md L6).
            scheduleAmtTimers(lifetimeInMs, lifetimeInMs + tutorialTimeInMs,
              new Runnable() {
                @Override
                public void run() {
                  gameListener.hasStarted();
                }
              },
              new Runnable() {
                @Override
                public void run() {
                  processScript("initStep.start()", breadboardOut, null);
                  Logger.debug("initStep.start()");
                }
              });
          }
        } else if (message instanceof Breadboard.SendScript) {
          Breadboard.SendScript sendScript = (Breadboard.SendScript) message;

          if (breadboardMessage.user != null) {
            // Save script in database
            breadboardMessage.user.currentScript = sendScript.script;
            breadboardMessage.user.update();

            // Process script
            processScript(sendScript.script, breadboardMessage.out, null);
          }

        } else if (message instanceof Breadboard.MakeChoice) {
          Breadboard.MakeChoice makeChoice = (Breadboard.MakeChoice) message;
          makeChoice(null, makeChoice.uid, makeChoice.params, breadboardMessage.out); // admin acting for a player
        } else if (message instanceof Breadboard.EditTie) {
          Breadboard.EditTie editTie = (Breadboard.EditTie) message;
          editTie(editTie.op, editTie.a, editTie.b, breadboardMessage.out);
        } else if (message instanceof Breadboard.SendStep) {
          Breadboard.SendStep sendStep = (Breadboard.SendStep) message;

          if (breadboardMessage.user != null && breadboardMessage.user.selectedExperiment != null) {
            Logger.debug("SendStep: " + sendStep.id + ", " + sendStep.name + ", " + sendStep.source);
            // Now we only run the step
            Breadboard.instances.get(breadboardMessage.user.email).tell(new Breadboard.RunStep(breadboardMessage.user, sendStep.source, breadboardMessage.out), null);
          }
        } else if (message instanceof Breadboard.RunStep) {
          // TODO: Compile step here and make available to scripting engine
          Breadboard.RunStep runStep = (Breadboard.RunStep) message;
          processScript(runStep.source, breadboardMessage.out, null);
        } else if (message instanceof Breadboard.ChangeExperiment) {
          if (breadboardMessage.user != null) {
            rebuildScriptBoard(breadboardMessage.user.selectedExperiment);
            loadSteps(breadboardMessage.user.selectedExperiment, breadboardMessage.out);
            Breadboard.ChangeExperiment changeExperiment = (Breadboard.ChangeExperiment) message;

            //reset the instance
            if (breadboardMessage.user.experimentInstanceId != -1) {
              ExperimentInstance runningInstance = ExperimentInstance.findById(breadboardMessage.user.experimentInstanceId);
              if (runningInstance != null) {
                if (runningInstance.status == ExperimentInstance.Status.RUNNING && runningInstance.experiment.id.equals(changeExperiment.experiment.id)) {
                  eventTracker.setExperimentInstance(runningInstance);
                  gameListener.experimentInstance = runningInstance;
                  for (Data param : runningInstance.data) {
                    initParam(param, changeExperiment.experiment);
                  }
                }
              } else {
                Logger.error("runningInstance == null");
              }
            }
          }
        } else if (message instanceof Breadboard.DeleteExperiment) {
          Breadboard.DeleteExperiment deleteExperiment = (Breadboard.DeleteExperiment) message;

          Experiment toBeDeleteExperiment = deleteExperiment.user.getExperimentByName(deleteExperiment.experimentName);
          Logger.debug("DeleteExperiment: " + toBeDeleteExperiment.name);

          //also stops the game
          for (Parameter p : breadboardMessage.user.getExperiment().getParameters()) {
            Logger.debug("Key: " + p.name);
            if (engine.getBindings(ScriptContext.ENGINE_SCOPE).containsKey(p.name))
              engine.getBindings(ScriptContext.ENGINE_SCOPE).remove(p.name);
          }

          breadboardMessage.user.currentScript = "";
          processScript("g.empty()", breadboardMessage.out, null);

          if (toBeDeleteExperiment != null) {
            //needs to remove all the selectedExperiment and ownedExperiments which is this toBeDeleteExperiment
            List<User> users = User.find.all();

            boolean update;
            boolean updateManyToMany;
            for (User user : users) {
              if (user.email.equalsIgnoreCase(breadboardMessage.user.email)) {
                user = breadboardMessage.user;
                user.setExperimentInstanceId(-1L);
              }
              update = false;
              updateManyToMany = false;
              if (user.selectedExperiment != null && user.selectedExperiment.id == toBeDeleteExperiment.id) {
                update = true;
                user.setSelectedExperiment(null);
              }
              if (user.ownedExperiments.contains(toBeDeleteExperiment)) {
                updateManyToMany = true;
                user.ownedExperiments.remove(toBeDeleteExperiment);
              }
              if (update) {
                user.update();
              }
              if (updateManyToMany) {
                user.saveManyToManyAssociations("ownedExperiments");
              }
            }

            toBeDeleteExperiment.delete();
          }

          Breadboard.breadboardController.tell(new Breadboard.Update(breadboardMessage.user, breadboardMessage.out), null);
        } else if (message instanceof Breadboard.LaunchGame) {
          Breadboard.LaunchGame launchGame = (Breadboard.LaunchGame) message;

          if (breadboardMessage.user.selectedExperiment != null) {
            rebuildScriptBoard(breadboardMessage.user.selectedExperiment);
            ExperimentInstance instance = startGame(launchGame.name, breadboardMessage.user.experimentInstanceId, launchGame.parameters,
                breadboardMessage.user.selectedExperiment, breadboardMessage.user);

            instanceData.addPropertyChangeListener(new UserDataChangeListener(instance));

            eventTracker.enable();
            eventTracker.setExperimentInstance(instance);
            gameListener.experimentInstance = instance;
            gameListener.start();

            // Re-run the Steps
            for (Step step : instance.experiment.getSteps())
              Breadboard.instances.get(breadboardMessage.user.email).tell(new Breadboard.RunStep(breadboardMessage.user, step.source, breadboardMessage.out), null);

          } // END if (breadboardMessage.user.selectedExperiment != null)
          // Update User JSON
          Breadboard.breadboardController.tell(new Breadboard.Update(breadboardMessage.user, breadboardMessage.out), null);

        } // END else if(message instanceof Breadboard.LaunchGame)
        else if (message instanceof Breadboard.SelectInstance) {
          Breadboard.SelectInstance selectInstance = (Breadboard.SelectInstance) message;
          rebuildScriptBoard(breadboardMessage.user.selectedExperiment);
          loadSteps(breadboardMessage.user.selectedExperiment, selectInstance.out);
          breadboardMessage.user.setExperimentInstanceId(selectInstance.id);
          breadboardMessage.user.update();

          gameListener.stop();
          ExperimentInstance instance = ExperimentInstance.findById(selectInstance.id);
          if (instance.status.isRunnable()) {
            List<Data> parameters = instance.data;
            for (Data param : parameters) {
              initParam(param, breadboardMessage.user.selectedExperiment);
            } // END for (Data param : parameters)

            eventTracker.enable();
            eventTracker.setExperimentInstance(instance);
            gameListener.experimentInstance = instance;
            gameListener.start();

            instanceData.addPropertyChangeListener(new UserDataChangeListener(instance));

            //don't know why... ebean bug? if don't do this, the instances in the selectedExperiment doesn't get updated
            //which is causing the launch button still shows up (because the status isn't updated) instead of the stop button for the instance
            breadboardMessage.user.selectedExperiment = Experiment.findById(breadboardMessage.user.selectedExperiment.id);
          }
          Breadboard.breadboardController.tell(new Breadboard.Update(breadboardMessage.user, breadboardMessage.out), null);
        } // END else if(message instanceof Breadboard.SelectInstance)
        else if (message instanceof Breadboard.StopGame) {
          Breadboard.StopGame stopGame = (Breadboard.StopGame) message;

          ExperimentInstance instance = ExperimentInstance.findById(stopGame.id);
          if (gameListener.experimentInstance != null && gameListener.experimentInstance.equals(instance)) {
            for (Parameter p : breadboardMessage.user.getExperiment().getParameters()) {
              //Logger.debug("Key: " + p.name);
              if (engine.getBindings(ScriptContext.ENGINE_SCOPE).containsKey(p.name))
                engine.getBindings(ScriptContext.ENGINE_SCOPE).remove(p.name);
            }

            if (gameListener.experimentInstance == null) {
              Logger.debug("gameListener.experimentInstance == null");
              gameListener.experimentInstance = ExperimentInstance.findById(breadboardMessage.user.experimentInstanceId);
            }

            if (gameListener.experimentInstance.status.isRunnable()) {
              Logger.debug("gameListener.experimentInstance.status.isRunnable()");
              gameListener.stop();
            }

          } else {
            if (instance.status.isRunnable()) {
              instance.stop();
            }
          }
          breadboardMessage.user.setExperimentInstanceId(-1L);
          breadboardMessage.user.update();
          // Update User JSON
          Breadboard.breadboardController.tell(new Breadboard.Update(breadboardMessage.user, breadboardMessage.out), null);
        } // END else if(message instanceof Breadboard.StopGame)
        else if (message instanceof Breadboard.RunOnJoinStep) {
          Breadboard.RunOnJoinStep runOnJoinStep = (Breadboard.RunOnJoinStep) message;
          runOnJoinStep.user.refresh();
          //the script engine was throwing exception for ArbesmanRand because of missing onJoinStep
          if (runOnJoinStep.user.selectedExperiment != null && runOnJoinStep.user.selectedExperiment.hasOnJoinStep()) {
            Vertex player = runOnJoinStep.vertex;
            String script = "onJoinStep.start(\"" + player.getId().toString() + "\")";
            processScript(script, runOnJoinStep.out, null);
          }

        } // END else if(message instanceof Breadboard.RunOnJoinStep)
        else if (message instanceof Breadboard.RunOnLeaveStep) {
          Breadboard.RunOnLeaveStep runOnLeaveStep = (Breadboard.RunOnLeaveStep) message;
          runOnLeaveStep.user.refresh();
          //the script engine was throwing exception for ArbesmanRand because of missing runOnLeaveStep
          if (runOnLeaveStep.user.selectedExperiment != null && runOnLeaveStep.user.selectedExperiment.hasOnLeaveStep()) {
            Vertex player = runOnLeaveStep.vertex;
            // This used to test the binding "onLeaveStep.start()", which nothing ever sets, so the
            // OnLeaveStep never ran. The step itself is bound as "onLeaveStep".
            if (engine.get("onLeaveStep") != null) {
              processScript("onLeaveStep.start(\"" + player.getId().toString() + "\")", runOnLeaveStep.out, null);
            }
          }

        } // END else if(message instanceof Breadboard.RunOnLeaveStep)
        else if (message instanceof Breadboard.Refresh) {
          Logger.debug("Breadboard.Refresh");

          //need to refresh the listeners
          for (Admin admin : admins) {
            admin.setOut(breadboardMessage.out);
          }

          //ObjectNode jsonOutput = Json.newObject();
          Graph wholeGraph = (Graph) engine.get("g");

          for (Admin admin : admins) {
            for (Vertex v : wholeGraph.getVertices()) {
              admin.vertexAdded(v, false);
            }
            for (Edge e : wholeGraph.getEdges()) {
              admin.edgeAdded(e);
            }
          }
          //breadboardMessage.out.write(jsonOutput);
        } else if (message instanceof Breadboard.GameFinish) {
          Breadboard.instances.get(breadboardMessage.user.email).tell(new Breadboard.ReloadEngine(breadboardMessage.user, breadboardMessage.out), null);
          Breadboard.breadboardController.tell(new Breadboard.Update(breadboardMessage.user, breadboardMessage.out), null);
        } else if (message instanceof Breadboard.ReloadEngine) {
          Experiment selectedExperiment = breadboardMessage.user.getExperiment();
          rebuildScriptBoard(selectedExperiment);
          loadSteps(selectedExperiment, breadboardMessage.out);
          Breadboard.ReloadEngine reloadEngine = (Breadboard.ReloadEngine) message;
          if (reloadEngine.user.experimentInstanceId != -1) {
            ExperimentInstance ei = ExperimentInstance.findById(reloadEngine.user.experimentInstanceId);
            if (ei != null) {
              eventTracker.enable();
              eventTracker.setExperimentInstance(ei);
              for (Data d : ei.data) {
                initParam(d, selectedExperiment);
              }
            }
          }
        }
      }
    } catch (Exception e) {
      ScriptLoader.humanizeStackTrace(e);
      Logger.error("Unhandled error in ScriptBoard.onReceive", e);
    }
  }

  private ExperimentInstance startGame(String gameName, long currentExperimentInstanceId, LinkedHashMap parameters, Experiment selectedExperiment, User user) {
    ExperimentInstance instance = new ExperimentInstance(gameName, selectedExperiment);
    instance.hasStarted = false;
    instance.status = ExperimentInstance.Status.STOPPED;

    if (currentExperimentInstanceId != -1) {
      ExperimentInstance runningInstance = ExperimentInstance.findById(currentExperimentInstanceId);
      if (runningInstance != null && runningInstance.status.isRunnable()) {
        runningInstance.status = ExperimentInstance.Status.STOPPED;
        runningInstance.update();
      }
    }

    initAllParam(parameters, selectedExperiment, instance);

    selectedExperiment.instances.add(instance);
    selectedExperiment.save();

    user.setExperimentInstanceId(instance.id);
    user.update();

    return instance;
  }

  private void initAllParam(Map params, Experiment experiment, ExperimentInstance instance) {

    Iterator it = params.keySet().iterator();
    while (it.hasNext()) {
      Object key = it.next();
      // Add the initial parameters to the data of the ExperimentInstance
      Data data = new Data();
      data.name = key.toString();
      data.value = params.get(key).toString();
      if (instance != null) {
        instance.data.add(data);
      }

      initParam(data, experiment);
    } // END while(it.hasNext())
  }

  private void initParam(Data param, Experiment experiment) {
    String key = param.name;
    Logger.debug("initParam: " + key);
    Parameter parameter = experiment == null ? null : experiment.getParameterByName(key);
    String type = parameter == null ? null : parameter.type;

    // Coercion logic lives in ScriptBoardSupport.coerceParam so it can be unit-tested
    // without the engine/DB. A null result means "leave the binding unset" -- which for
    // Integer/Decimal indicates a parse failure worth logging (as the original did).
    Object coerced = ScriptBoardSupport.coerceParam(type, param.value);
    if (coerced != null) {
      engine.getBindings(ScriptContext.ENGINE_SCOPE).put(key, coerced);
    } else if ("Integer".equals(type) || "Decimal".equals(type)) {
      Logger.error("Breadboard.LaunchGame: Caught NumberFormatException parsing string as "
          + type + ": " + param.value);
    }
  }

  /** Log an event for the current instance from outside the script engine (e.g. an entry route). */
  public static void trackEvent(String name, LinkedHashMap<Object, Object> data) {
    if (eventTracker != null) {
      eventTracker.track(name, data);
    }
  }

  /**
   * Record whether a participant currently has an open socket: {@code player._system.connected}
   * on the vertex (engine-internal, never sent to neighbors) and a clientConnected /
   * clientDisconnected event in the log.
   */
  static void setConnected(String clientId, boolean connected) {
    try {
      if (engine != null) {
        Object g = engine.get("g");
        if (g instanceof Graph) {
          Vertex v = ((Graph) g).getVertex(clientId);
          if (v != null) {
            Object system = v.getProperty("_system");
            if (system instanceof Map) {
              ((Map<String, Object>) system).put("connected", connected);
            }
          }
        }
      }
      if (eventTracker != null) {
        LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
        data.put("clientId", clientId);
        eventTracker.track(connected ? "clientConnected" : "clientDisconnected", data);
      }
      // Scripts can react, e.g. groovy/autopilot.groovy hands a departed player to the AI.
      Map<String, Object> info = new HashMap<String, Object>();
      info.put("clientId", clientId);
      info.put("connected", connected);
      eventBus.emit("bb:connection", info);
    } catch (Exception e) {
      Logger.error("Could not record connection state for " + clientId, e);
    }
  }

  private static void makeChoice(String clientId, String uid, String params, ThrottledWebSocketOut out) {
    ObjectNode jsonOutput = Json.newObject();
    try {
      // A choice uid is only valid for the participant it was shown to. clientId is null when an
      // admin makes the choice on a player's behalf from the admin panel.
      String owner = (playerActions == null || uid == null) ? null : playerActions.ownerOf(uid);
      if (clientId != null && (owner == null || !owner.equals(clientId))) {
        Logger.info("Ignored choice " + uid + " from " + clientId + ": not offered to this participant");
        out.write(jsonOutput);
        return;
      }
      playerActions.choose(uid, params);
    } catch (Exception e) {
      ScriptLoader.humanizeStackTrace(e);
      Logger.error("Failed to make choice " + uid, e);
      jsonOutput.put("error", "Caught error: " + ScriptBoardSupport.describeError(e) + "\n");
    }
    out.write(jsonOutput);
  }

  public static void processScript(String script, ThrottledWebSocketOut out, String scriptName) {
    if (scriptName == null) scriptName = "Unnamed Script";

    ObjectNode jsonOutput = Json.newObject();
    //TODO: better way to handle this?
    boolean initStep = false;
    synchronized (ScriptBoard.class) {
      if (script.contains("initStep.start()")) {
        if (engine.get("initStep.start()") != null) {
          Logger.debug("engine.get(\"initStep.start()\") = " + engine.get("initStep.start()").toString());
          Logger.warn("The initStep has started already.");
          jsonOutput.put("error", "Caught error: ".concat("initStep started already").concat("\n"));
          if (out != null) {
            out.write(jsonOutput);
          }
          return;
        }
        engine.put("initStep.start()", true);
        initStep = true;
        // Close the game to newcomers once it starts, however it was started. Only the AMT timer
        // used to do this, so with a manual start late joiners were admitted mid-game.
        if (!Play.application().configuration().getBoolean("breadboard.allowLateJoin", false)) {
          Object listener = engine.get("gameListener");
          if (listener instanceof GameListener) {
            ((GameListener) listener).hasStarted();
          }
        }
      }
    }
    if (initStep) {
      // Scripts can react however the game was started (admin, AMT timer, a script), e.g.
      // groovy/autopilot.groovy clears its waiting screen. Sent outside the lock above: a handler
      // may hold its own lock and call back in here.
      Map<String, Object> info = new HashMap<String, Object>();
      info.put("scriptName", scriptName);
      eventBus.emit("bb:game-start", info);
    }
    try {
      Object outputObject = engine.eval(script);
      // Clear out error
      jsonOutput.put("error", "");

      // Write out the results Map as JSON
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

      mapper.writeValue(outputStream, engine.getBindings(ScriptContext.ENGINE_SCOPE).get("results"));
      String outputString = script.trim().concat("\n\n==>");

      if (outputObject != null) {
        if (outputObject instanceof GremlinGroovyPipeline) {
          outputString += StringUtils.join(((GremlinGroovyPipeline) outputObject).toList(), "\n==>");
        } else {
          outputString += outputObject.toString();
        }
      }

      jsonOutput.put("output", outputString.trim());
    } catch (CompilationFailedException cfe) {
      // Translate Groovy's opaque "Script<N>.groovy" names back to real file names in both the
      // logged stack trace and the error sent to the browser (see ScriptLoader.humanize*).
      ScriptLoader.humanizeStackTrace(cfe);
      Logger.error("Unable to compile the script. " + scriptName, cfe);
      jsonOutput.put("error", "Caught error: " + ScriptBoardSupport.describeError(cfe) + "\n");
      if (initStep) {
        engine.put("initStep.start()", null);
      }
    } catch (ScriptException se) {
      ScriptLoader.humanizeStackTrace(se);
      Logger.error("Script Error. " + scriptName, se);
      jsonOutput.put("error", "Caught error: " + ScriptBoardSupport.describeError(se) + "\n");
      if (initStep) {
        engine.put("initStep.start()", null);
      }
    } catch (Exception e) {
      ScriptLoader.humanizeStackTrace(e);
      Logger.error("Failed to process the script. " + scriptName, e);
      jsonOutput.put("error", "Caught error: " + ScriptBoardSupport.describeError(e) + "\n");
      if (initStep) {
        engine.put("initStep.start()", null);
      }
    } finally {
      if (out != null) {
        out.write(jsonOutput);
      }
    }
  }

  /**
   * A researcher adding or cutting a tie from the admin network view, through
   * edgeEdits.adminEdit (groovy/edge_edits.groovy), which logs it. The admin is told the outcome as
   * {"tieEdit": {ok, op, a, b, reason}}; the tie itself reaches it with the next graph update.
   */
  static void editTie(String op, String a, String b, ThrottledWebSocketOut out) {
    ObjectNode result = Json.newObject();
    result.put("op", op);
    result.put("a", a);
    result.put("b", b);
    String reason = null;
    if (!"add".equals(op) && !"remove".equals(op)) {
      reason = "Unknown edit \"" + op + "\"";
    } else if (engine == null || !(engine.get("edgeEdits") instanceof groovy.lang.GroovyObject)) {
      reason = "The experiment's engine isn't loaded yet.";
    } else {
      try {
        ((groovy.lang.GroovyObject) engine.get("edgeEdits")).invokeMethod("adminEdit", new Object[]{op, a, b});
      } catch (Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
          root = root.getCause();
        }
        reason = root.getMessage() != null ? root.getMessage() : root.toString();
      }
    }
    result.put("ok", reason == null);
    if (reason != null) {
      result.put("reason", reason);
    }
    ObjectNode message = Json.newObject();
    message.put("tieEdit", result);
    if (out != null) {
      out.write(message);
    }
  }

  private class UserDataChangeListener implements PropertyChangeListener {

    private ExperimentInstance experimentInstance;

    private UserDataChangeListener(ExperimentInstance experimentInstance) {
      this.experimentInstance = experimentInstance;
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
      List<Data> dataList = experimentInstance.data;
      Data foundData = null;
      if (evt.getPropertyName() != null) {
        foundData = findDataByName(evt.getPropertyName());
      }
      if (evt instanceof ObservableMap.PropertyAddedEvent) {
        Data newData = foundData == null ? new Data() : foundData;
        newData.name = evt.getPropertyName();
        newData.value = evt.getNewValue().toString();
        newData.experimentInstance = experimentInstance;
        if (foundData == null) {
          dataList.add(newData);
          experimentInstance.save();
        } else {
          newData.save();
        }
        initParam(newData, null);

      } else if (evt instanceof ObservableMap.PropertyClearedEvent) {
        List<Parameter> parameters = experimentInstance.experiment.getParameters();
        List<String> parameterNames = new ArrayList<String>();
        for (Parameter parameter : parameters) {
          parameterNames.add(parameter.name);
        }
        Iterator<Data> dataIterator = dataList.iterator();
        while (dataIterator.hasNext()) {
          Data d = dataIterator.next();
          //make sure not removing the parameters
          if (!parameterNames.contains(d.name)) {
            dataIterator.remove();
            engine.getBindings(ScriptContext.ENGINE_SCOPE).remove(d.name);
          }
        }
        experimentInstance.save();

      } else if (evt instanceof ObservableMap.PropertyRemovedEvent) {
        if (foundData != null) {
          dataList.remove(foundData);
          engine.getBindings(ScriptContext.ENGINE_SCOPE).remove(foundData.name);
        }
        experimentInstance.save();
      } else if (evt instanceof ObservableMap.PropertyUpdatedEvent) {
        if (foundData != null) {
          foundData.value = evt.getNewValue().toString();
        }
        foundData.save();
        initParam(foundData, null);
      }
    }

    private Data findDataByName(String name) {
      List<Data> dataList = experimentInstance.data;
      for (Data d : dataList) {
        //this should be case-sensitive
        if (d.name.equals(name)) {
          return d;
        }
      }
      return null;
    }
  }
}
