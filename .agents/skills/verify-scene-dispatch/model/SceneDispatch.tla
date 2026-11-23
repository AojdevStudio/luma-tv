---------------------------- MODULE SceneDispatch ----------------------------
(* One confirmed House Control scene, from Confirm to the result the user sees.
   Every action names the code it stands for. Main-thread actions are atomic,
   because Android runs each main-thread callback to its end. The HouseLink
   worker and House Control run beside the main thread. *)
EXTENDS Naturals, Sequences

CONSTANTS
    CheckAtTake,  \* HouseLink.run asks the refusal when the worker takes the scene (6aa4885)
    PaneOwned,    \* runAction renders a result only into the pane that started it (fc47a32)
    KeepUnseen,   \* runAction keeps a result nobody saw and clears Starting (#26)
    ReplyPlan,    \* HouseScenes.outcome compares the plan revision in an ok reply with the preview's (#29 review)
    Unreadable,   \* HouseLink.send calls a reply it cannot decode Unknown, not Not run (#29 comment audit)
    MaxRev        \* plan imports House Control may make during one run

VARIABLES
    mode,          \* Profiles mode: "Normal" or "Child"
    planRev,       \* HomeEngine.revision, the plan House Control runs scenes from
    lumaRev,       \* MainActivity.houseRead's revision
    previewRev,    \* Scene.revision of the action the user confirmed
    run,           \* "previewed" "reading" "queued" "sending" "finished"
    queue,         \* HouseLink.queue, on the main thread
    busy,          \* HouseLink worker: "none" or the request it holds
    posted,        \* the worker finished and posted its answer to the main thread
    bound,         \* HouseLink.binder != null
    readSeen,      \* the revision the worker's code 1 read saw
    others,        \* reads other panel code queued (loadScenes)
    sent,          \* scenes HomeEngine.action ran for this Confirm
    modeAtHandOff, \* mode when the worker took the scene
    ranRev,        \* the revision House Control ran the scene at
    outcome,       \* "none" "ok" "error" "lost" "unreadable" "refused"
    resStatus,     \* Capabilities.Status of the result, or "none"
    flagged,       \* the result says House Control ran another plan
    delivery,      \* where the result went
    overlay,       \* capabilityOverlay is this panel: "open" or "closed"
    pane,          \* paneChanges still equals the run's pane: "run", else "other"
    focus,         \* "button" (primary has focus) or "rows"
    onScreen,      \* MainActivity.started
    status,        \* the pane's status line: "confirm" "starting" "result" "preview"
    clobbered,     \* a result replaced a newer pane
    movedInSend,   \* history: the user moved to another pane while the worker held the scene
    leftInSend     \* history: Luma left the screen while the worker held the scene

vars == <<mode, planRev, lumaRev, previewRev, run, queue, busy, posted, bound, readSeen, others,
          sent, modeAtHandOff, ranRev, outcome, resStatus, flagged, delivery,
          overlay, pane, focus, onScreen, status, clobbered, movedInSend, leftInSend>>
history == <<movedInSend, leftInSend>>
ui == <<overlay, pane, focus, onScreen>>

Init ==
    /\ mode = "Normal" /\ planRev = 0 /\ lumaRev = 0 /\ previewRev = 0
    /\ run = "previewed" /\ queue = <<>> /\ busy = "none" /\ posted = FALSE /\ bound = FALSE
    /\ readSeen = 0 /\ others = 0 /\ sent = 0 /\ modeAtHandOff = "none" /\ ranRev = 0 /\ outcome = "none"
    /\ resStatus = "none" /\ flagged = FALSE /\ delivery = "none"
    /\ overlay = "open" /\ pane = "run" /\ focus = "button" /\ onScreen = TRUE /\ status = "confirm"
    /\ clobbered = FALSE /\ movedInSend = FALSE /\ leftInSend = FALSE

(* Capabilities.refusal: the mode allows scenes and HouseScenes.refusal finds the cached read equal to the preview. *)
Allowed(rev) == mode = "Normal" /\ rev = previewRev

(* HouseLink.next runs in the same main-thread callback as request() or an answer. A main action may start only
   when that loop has nothing left to take. *)
Settled == ~(busy = "none" /\ bound /\ queue # <<>>)

(* runAction's callback: where the result of this run goes. *)
Deliver(st) ==
    LET shows == overlay = "open" /\ focus = "button" /\ (pane = "run" \/ ~PaneOwned)
        owns  == overlay = "open" /\ pane = "run"
    IN /\ resStatus' = st
       /\ run' = "finished"
       /\ IF shows THEN /\ delivery' = "pane" /\ status' = "result" /\ clobbered' = (pane # "run")
          ELSE IF KeepUnseen /\ owns THEN /\ delivery' = "status" /\ status' = "result" /\ UNCHANGED clobbered
          ELSE IF onScreen THEN /\ delivery' = "toast" /\ UNCHANGED <<status, clobbered>>
          ELSE IF KeepUnseen THEN /\ delivery' = "held" /\ UNCHANGED <<status, clobbered>>
          ELSE IF st = "DONE" THEN /\ delivery' = "history" /\ UNCHANGED <<status, clobbered>>
          ELSE /\ delivery' = "lost" /\ UNCHANGED <<status, clobbered>>

(* CapabilityCenter.runAction -> Capabilities.run: the first refusal, then readHouse queues a code 1 read. *)
Confirm ==
    /\ Settled /\ run = "previewed" /\ overlay = "open" /\ pane = "run" /\ focus = "button"
    /\ IF Allowed(lumaRev)
         THEN /\ run' = "reading" /\ status' = "starting" /\ queue' = Append(queue, "run-read")
              /\ UNCHANGED <<resStatus, delivery, clobbered>>
         ELSE /\ Deliver("NOT_RUN") /\ UNCHANGED queue
    /\ UNCHANGED <<mode, planRev, lumaRev, previewRev, busy, posted, bound, readSeen, others,
                   sent, modeAtHandOff, ranRev, outcome, flagged, ui, history>>

(* CapabilityCenter.loadScenes, from choosing the Scenes section again: another code 1 read. *)
OtherRead ==
    /\ Settled /\ others < 1 /\ overlay = "open"
    /\ others' = others + 1 /\ queue' = Append(queue, "other")
    /\ UNCHANGED <<mode, planRev, lumaRev, previewRev, run, busy, posted, bound, readSeen, sent, modeAtHandOff,
                   ranRev, outcome, resStatus, flagged, delivery, ui, status, clobbered, history>>

(* HouseLink.connect -> onServiceConnected, which calls next(). *)
Bind ==
    /\ ~bound /\ queue # <<>> /\ bound' = TRUE
    /\ UNCHANGED <<mode, planRev, lumaRev, previewRev, run, queue, busy, posted, readSeen, others, sent, modeAtHandOff,
                   ranRev, outcome, resStatus, flagged, delivery, ui, status, clobbered, history>>

(* HouseLink.release after IDLE_MS with nothing queued. *)
Unbind ==
    /\ Settled /\ bound /\ busy = "none" /\ queue = <<>> /\ bound' = FALSE
    /\ UNCHANGED <<mode, planRev, lumaRev, previewRev, run, queue, busy, posted, readSeen, others, sent, modeAtHandOff,
                   ranRev, outcome, resStatus, flagged, delivery, ui, status, clobbered, history>>

(* HouseLink.next: the worker is free, so it takes the head. Before 6aa4885 the scene was not checked here. *)
Take ==
    /\ busy = "none" /\ bound /\ queue # <<>>
    /\ queue' = Tail(queue)
    /\ IF Head(queue) = "scene"
         THEN IF CheckAtTake /\ ~Allowed(lumaRev)
                THEN /\ busy' = "none" /\ Deliver("NOT_RUN") /\ UNCHANGED modeAtHandOff
                ELSE /\ busy' = "scene" /\ run' = "sending" /\ modeAtHandOff' = mode
                     /\ UNCHANGED <<resStatus, delivery, status, clobbered>>
         ELSE /\ busy' = Head(queue) /\ UNCHANGED <<run, modeAtHandOff, resStatus, delivery, status, clobbered>>
    /\ UNCHANGED <<mode, planRev, lumaRev, previewRev, posted, bound, readSeen, others, sent, ranRev, outcome,
                   flagged, ui, history>>

(* HouseLink.fetch or send on the worker thread: one Binder transaction. HomeEngine.action runs the scene by id,
   under actionLock, from the plan it holds at that moment. A lost or unreadable reply may follow a run.
   HomeService refuses an untrusted Luma with a SecurityException before it runs anything. *)
Transact ==
    /\ busy # "none" /\ ~posted /\ posted' = TRUE
    /\ IF busy = "scene"
         THEN \/ /\ sent' = sent + 1 /\ ranRev' = planRev /\ outcome' \in {"ok", "error", "lost", "unreadable"}
                 /\ UNCHANGED readSeen
              \/ /\ outcome' = "refused" /\ UNCHANGED <<sent, ranRev, readSeen>>
         ELSE /\ readSeen' = planRev /\ UNCHANGED <<sent, ranRev, outcome>>
    /\ UNCHANGED <<mode, planRev, lumaRev, previewRev, run, queue, busy, bound, others, modeAtHandOff,
                   resStatus, flagged, delivery, ui, status, clobbered, history>>

(* The worker's main.post: busy = false, answer, next(). A read stores houseRead. The run's own read then calls
   tv.runScene, which queues the scene. Before 6aa4885 the refusal was asked here instead of at Take. *)
AnswerRead ==
    /\ Settled /\ posted /\ busy \in {"run-read", "other"}
    /\ busy' = "none" /\ posted' = FALSE /\ lumaRev' = readSeen
    /\ IF busy = "run-read"
         THEN IF ~CheckAtTake /\ ~(mode = "Normal" /\ readSeen = previewRev)
                THEN /\ Deliver("NOT_RUN") /\ UNCHANGED queue
                ELSE /\ run' = "queued" /\ queue' = Append(queue, "scene")
                     /\ UNCHANGED <<resStatus, delivery, status, clobbered>>
         ELSE UNCHANGED <<run, queue, resStatus, delivery, status, clobbered>>
    /\ UNCHANGED <<mode, planRev, previewRev, bound, readSeen, others, sent, modeAtHandOff, ranRev, outcome,
                   flagged, ui, history>>

(* The scene's answer: HouseScenes.outcome, then Capabilities.finish, then runAction's callback. *)
AnswerScene ==
    /\ Settled /\ posted /\ busy = "scene"
    /\ busy' = "none" /\ posted' = FALSE
    /\ flagged' = (ReplyPlan /\ outcome \in {"ok", "error"} /\ ranRev # previewRev)
    /\ Deliver(CASE outcome = "ok" -> "DONE" [] outcome = "error" -> "NOT_FINISHED" [] outcome = "lost" -> "UNKNOWN"
                  [] outcome = "refused" -> "NOT_RUN" [] OTHER -> IF Unreadable THEN "UNKNOWN" ELSE "NOT_RUN")
    /\ UNCHANGED <<mode, planRev, lumaRev, previewRev, queue, bound, readSeen, others, sent, modeAtHandOff,
                   ranRev, outcome, ui, history>>

(* HomeEngine.importHome or setDemo: a new plan and revision. The same scene id may now change other devices. *)
Import ==
    /\ planRev < MaxRev /\ planRev' = planRev + 1
    /\ UNCHANGED <<mode, lumaRev, previewRev, run, queue, busy, posted, bound, readSeen, others, sent, modeAtHandOff,
                   ranRev, outcome, resStatus, flagged, delivery, ui, status, clobbered, history>>

UserUnchanged == UNCHANGED <<planRev, lumaRev, previewRev, run, queue, busy, posted, bound, readSeen, others, sent,
                             modeAtHandOff, ranRev, outcome, resStatus, flagged, delivery, clobbered>>

(* Back moves focus off the button to the section list. The pane is not rebuilt. *)
Back ==
    /\ Settled /\ overlay = "open" /\ focus = "button" /\ focus' = "rows"
    /\ UNCHANGED <<mode, overlay, pane, onScreen, status, history>> /\ UserUnchanged

(* Back again closes the panel. *)
Close ==
    /\ Settled /\ overlay = "open" /\ focus = "rows" /\ overlay' = "closed"
    /\ UNCHANGED <<mode, pane, focus, onScreen, status, history>> /\ UserUnchanged

(* Focus another scene row (showAction calls pane()), and maybe OK on it, which focuses its Confirm. *)
MoveOn ==
    /\ Settled /\ overlay = "open" /\ pane' = "other" /\ status' = "preview" /\ focus' \in {"rows", "button"}
    /\ movedInSend' = (movedInSend \/ run = "sending")
    /\ UNCHANGED <<mode, overlay, onScreen, leftInSend>> /\ UserUnchanged

(* The Mode section: select() rebuilds the pane, then Child is chosen. *)
ChildMode ==
    /\ Settled /\ overlay = "open" /\ mode = "Normal"
    /\ mode' = "Child" /\ pane' = "other" /\ focus' = "rows" /\ status' = "preview"
    /\ movedInSend' = (movedInSend \/ run = "sending")
    /\ UNCHANGED <<overlay, onScreen, leftInSend>> /\ UserUnchanged

(* onPause and onResume. *)
Leave ==
    /\ Settled /\ onScreen /\ onScreen' = FALSE
    /\ leftInSend' = (leftInSend \/ run = "sending")
    /\ UNCHANGED <<mode, overlay, pane, focus, status, movedInSend>> /\ UserUnchanged
Return ==
    /\ Settled /\ ~onScreen /\ onScreen' = TRUE
    /\ delivery' = IF delivery = "held" THEN "shown-on-return" ELSE delivery
    /\ UNCHANGED <<mode, planRev, lumaRev, previewRev, run, queue, busy, posted, bound, readSeen, others, sent,
                   modeAtHandOff, ranRev, outcome, resStatus, flagged, overlay, pane, focus, status, clobbered, history>>

Next ==
    \/ Confirm \/ OtherRead \/ Bind \/ Unbind \/ Take \/ Transact \/ AnswerRead \/ AnswerScene \/ Import
    \/ Back \/ Close \/ MoveOn \/ ChildMode \/ Leave \/ Return

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
(* Properties. Each names the harm when it breaks. *)

TypeOK ==
    /\ mode \in {"Normal", "Child"} /\ planRev \in 0..MaxRev /\ sent \in 0..1
    /\ run \in {"previewed", "reading", "queued", "sending", "finished"}
    /\ delivery \in {"none", "pane", "status", "toast", "held", "shown-on-return", "history", "lost"}
    /\ movedInSend \in BOOLEAN /\ leftInSend \in BOOLEAN

(* A scene reaches House Control only when Normal mode held at the moment the worker took it. *)
NoChildHandOff == sent > 0 => modeAtHandOff = "Normal"

(* A late result never replaces the preview or Confirm of another scene. *)
ResultInOwnPane == ~clobbered

(* The same harm for a scene House Control really ran. Its counterexample must hand the scene to the worker, send it,
   move to another pane, and then deliver the late reply. A refusal at the take cannot stand in for it. *)
ResultInOwnPaneAfterSend == (sent = 1 /\ movedInSend) => ~clobbered

(* A failed or unknown result is never dropped unseen. Done results also stay in the history line. *)
NoLostResult == delivery # "lost"

(* Issue #26 as it happened, one property per failure. House Control ran the scene, Luma left the screen while the
   worker held it, and the late reply is a provider error (NoLostFailure) or a lost reply (NoLostReply). *)
NoLostFailure == (sent = 1 /\ leftInSend /\ outcome = "error") => delivery # "lost"
NoLostReply == (sent = 1 /\ leftInSend /\ outcome = "lost") => delivery # "lost"

(* The pane that started the run never still says Starting after the run ended. *)
NoStaleStarting == (run = "finished" /\ overlay = "open" /\ pane = "run") => status # "starting"

(* A result for a scene House Control ran from another plan says so. *)
RanPlanReported ==
    (run = "finished" /\ sent > 0 /\ resStatus \in {"DONE", "NOT_FINISHED"} /\ ranRev # previewRev) => flagged

(* Not run means House Control ran nothing. *)
NotRunMeansNothingRan == resStatus = "NOT_RUN" => sent = 0

(* One Confirm sends at most once. *)
AtMostOneSend == sent <= 1

(* Controls. Each must break, which proves its refusal path is still reachable. A property that holds only because
   these paths vanished from the model proves nothing. *)
(* A scene queued behind a read is refused at the take because Child mode began. Nothing was handed off or sent. *)
NeverQueuedChildRefusal == ~(mode = "Child" /\ modeAtHandOff = "none" /\ resStatus = "NOT_RUN" /\ sent = 0)
(* House Control refused the transaction before it ran anything: an untrusted Luma signer or a missing code. *)
NeverRefusedBeforeRun == ~(outcome = "refused" /\ resStatus = "NOT_RUN" /\ sent = 0)

(* Not provable inside Luma: House Control runs the plan the user confirmed. Code 2 carries only the scene id,
   so an import between Luma's last check and the transaction changes what runs. Checked to show the counterexample. *)
RanAsConfirmed == sent > 0 => ranRev = previewRev
=============================================================================
