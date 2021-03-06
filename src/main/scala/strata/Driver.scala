package strata

import java.io.File
import java.util.concurrent._

import org.joda.time.DateTime
import strata.data._
import strata.tasks._
import strata.util.ColoredOutput._
import strata.util.IO

import scala.util.Random

/**
 * Driver for a full run of strata.  Takes care of deciding what to run next.
 */
class Driver(initOptions: InitOptions) {

  val globalOptions = initOptions.globalOptions
  val state = State(globalOptions)
  val alreadySeen = collection.mutable.Set[Instruction]()
  val isImm8Exp = initOptions.imm_instructions

  def run(args: Array[String], continue: Boolean = false): Unit = {
    // initialize
    if (!continue) {
      Initialize.run(args, initOptions)
    } else {
      if (!state.exists) IO.error("workdir does not exist, cannot continue")
      state.appendLog(LogEntryPoint(args))
    }

    // TODO better value
    var nThreads = Runtime.getRuntime.availableProcessors() * 3 / 4
    IO.info(s"Running with $nThreads threads")

    // our pool of threads to do all the computation
    val executor = Executors.newFixedThreadPool(nThreads)
    val threadPool = new ExecutorCompletionService[TaskResult](executor)
    var tasksRunning = 0
    val taskMap = collection.mutable.HashMap[Future[TaskResult], Task]()

    /** start a new task */
    def startNewTask(task: Task): Unit = {
      val callable: Callable[TaskResult] = new Callable[TaskResult] {
        def call(): TaskResult = {
          runTask(task)
        }
      }
      tasksRunning += 1
      val future = threadPool.submit(callable)
      taskMap.put(future, task)
    }

    /** Start up to n new tasks. */
    def startNewTasks(n: Int): Unit = {
      for (i <- 1 to n) {
        selectNextTask() match {
          case None =>
          case Some(t) => startNewTask(t)
        }
      }
    }

    // initial set of tasks
    startNewTasks(nThreads)

    // main loop
    while (tasksRunning > 0) {
      val future = threadPool.take()
      finishTask(taskMap(future), future)
      taskMap.remove(future)
      tasksRunning -= 1

      // start new tasks
      startNewTasks(nThreads - tasksRunning)
    }

    executor.shutdown()
    IO.info("Finished all tasks")
  }

  /** Execute a task. */
  private def runTask(task: Task): TaskResult = {
    task.runnerContext = ThreadContext.self
    task match {
      case t: InitialSearchTask =>
        InitialSearch.run(t)
      case t: SecondarySearchTask =>
        SecondarySearch.run(t)
    }
  }

  /** Run a task asynchronously. */
  private var poolForOthers: ExecutorService = null

  def runTaskAsync(task: Task): Future[TaskResult] = {
    poolForOthers = Executors.newFixedThreadPool(1)
    poolForOthers.submit(new Callable[TaskResult] {
      override def call(): TaskResult = runTask(task)
    })
  }

  def endAsync(): Unit = {
    poolForOthers.shutdown()
  }

  /** Finish a task, correctly handling errors and taks results. */
  def finishTask(task: Task, future: Future[TaskResult]): Unit = {
    try {
      val taskRes = future.get()
      val pseudoTimeEnd = handleTaskResult(taskRes)
      state.appendLog(LogTaskEnd(task, Some(taskRes), pseudoTimeEnd, DateTime.now, task.runnerContext))
    } catch {
      case t: Throwable =>
        state.appendLog(LogTaskEnd(task, None, -1, DateTime.now, task.runnerContext))
        val cause = if (t.getCause != null) t.getCause else t
        state.appendLog(LogError(s"exception in task: ${t.getMessage}\n${cause.getStackTrace.mkString("\n")}"))
        state.lockedInformation(() => {
          state.removeInstructionToFile(task.instruction, InstructionFile.Worklist)
        })
    }
  }

  /** Handle the result of a task. */
  private def handleTaskResult(taskRes: TaskResult): Int = {
    val task = taskRes.task
    val instr = taskRes.instruction
    val minEqClassSize = 2
    def moveProgramToCircuitDir(meta: InstructionMeta, n: Int): Unit = {
      val eqClasses = meta.equivalence_classes.getClasses(minEqClassSize)
      // copy a file to the circuits directory
      val resCircuit = new File(s"${state.getCircuitDir}/$instr.s")
      val score = if (eqClasses.isEmpty) {
//        if (n != -1) {
//          val msg = s"Found $n programs for $instr, but no equivalence class has size at least $minEqClassSize"
//          state.appendLog(LogError(msg))
//        }
        val best = meta.equivalence_classes.getClasses().head.sortedPrograms.head
        IO.copyFile(best.getFile(instr, state), resCircuit)
        best.score
      } else {
        // determine which program is the best according to our heuristics
        val best = eqClasses.head.getRepresentativeProgram
        IO.copyFile(best.getFile(instr, state), resCircuit)
        best.score
      }
      state.addScore(instr, score)
      state.appendLog(LogEquivalenceClasses(instr, meta.equivalence_classes))
    }
    state.lockedInformation(() => {
      taskRes match {
        case _: InitialSearchSuccess =>
          // special case nop's, because we never learn more than 1 program for them anyway
          if (Vector("nop", "nopw_r16", "nopl_r32").contains(instr.opcode)) {
            state.removeInstructionToFile(instr, InstructionFile.RemainingGoal)
            if (!initOptions.no_stratification) {
              state.addInstructionToFile(instr, InstructionFile.Success)
            }
            val meta = state.getMetaOfInstr(task.instruction)
            moveProgramToCircuitDir(meta, -1)
          } else {
            state.removeInstructionToFile(instr, InstructionFile.RemainingGoal)
            state.addInstructionToFile(instr, InstructionFile.PartialSuccess)
          }
          IO.info(s"IS success for ${task.instruction}")
        case ist: InitialSearchTimeout =>
          IO.info(s"IS timeout for ${task.instruction} after ${ist.task.budget} iters / ${IO.formatNanos(ist.timing.total)}")
        case _: InitialSearchError =>
        case _: SecondarySearchError =>
        case _: SecondarySearchSuccess =>
          val meta = state.getMetaOfInstr(task.instruction)
          val classes = meta.equivalence_classes.getClasses(minEqClassSize)
          val uifsInBest = if (classes.nonEmpty) {
            classes.head.sortedPrograms.head.score.uif
          } else {
            1
          }
          val n = state.getResultFiles(instr).length // number of programs found
          IO.info(s"SS success #$n for ${task.instruction}")
          // should we search for non-uif codes?
          if (n >= 10 && uifsInBest > 0 && !meta.search_without_uif) {
            IO.info(s"  -> moving on to non-uif search for $instr")
            state.writeMetaOfInstr(instr, meta.copy(search_without_uif = true))
          }
          // stop after we found enough
          if ((n >= 15 && uifsInBest == 0) || (n >= 20)) {
            moveProgramToCircuitDir(meta, n)
            state.removeInstructionToFile(instr, InstructionFile.PartialSuccess)
            if (!initOptions.no_stratification) {
              state.addInstructionToFile(instr, InstructionFile.Success)
            }
          }
        case _: SecondarySearchTimeout =>
          val meta = state.getMetaOfInstr(task.instruction)
          val n = meta.secondary_searches.map(s => s.n_found).sum + 1 // +1 for initial search
          IO.info(s"SS timeout for ${task.instruction} after ${IO.formatNanos(taskRes.timing.total)}")
          // stop if we tried 5 times and didn't succeed
          if (meta.secondary_searches.length >= 3) {
            moveProgramToCircuitDir(meta, n)
            state.removeInstructionToFile(instr, InstructionFile.PartialSuccess)
            if (!initOptions.no_stratification) {
              state.addInstructionToFile(instr, InstructionFile.Success)
            }
          }
      }
      state.removeInstructionToFile(instr, InstructionFile.Worklist)
      state.getPseudoTime
    })
  }

  /** Compute the budget for the initial search. */
  def initialSearchBudget(instr: Instruction): Long = {
    val pnow = state.getPseudoTime
    val meta = state.getMetaOfInstr(instr)
    val default = if (instr.isImm8Instr) {
      // there is nothing that gets added to the base set, so spend more initially
      800000
    } else {
      200000
    }
    var res: Double = default
    for (initalSearch <- meta.initial_searches) {
      res += Math.pow(1.5, -(pnow - initalSearch.start_ptime).toDouble / 10.0) * initalSearch.iterations
    }
    Math.min(res.toLong, 100000000)
  }

  /** Compute the budget for the secondary search. */
  def secondarySearchBudget(instr: Instruction, search_without_uif: Boolean): Long = {
    if (instr.isImm8Instr) {
      if (search_without_uif) 500000 else 1000000
    } else {
      if (search_without_uif) 1000000 else 10000000
    }
  }

  /** Select what next step should be done, and puts the task into the worklist. */
  def selectNextTask(instruction: Option[Instruction] = None): Option[Task] = {
    def mkInitialSearch(instr: Instruction, pseudoTime: Int): Option[Task] = {
      val budget = initialSearchBudget(instr)
      state.addInstructionToFile(instr, InstructionFile.Worklist)
      Some(InitialSearchTask(state.globalOptions, instr, budget, pseudoTime))
    }

    def mkSecondarySearch(instr: Instruction, pseudoTime: Int): Option[Task] = {
      val meta = state.getMetaOfInstr(instr)
      val budget = secondarySearchBudget(instr, meta.search_without_uif)
      state.addInstructionToFile(instr, InstructionFile.Worklist)
      Some(SecondarySearchTask(state.globalOptions, instr, budget, pseudoTime))
    }

    state.lockedInformation(() => {

      // should we stop?
      if (state.signalShutdownReceived) {
        return None
      }

      val pseudoTime = state.getPseudoTime
      val goal = state.getInstructionFile(InstructionFile.RemainingGoal).filter(i => !blacklist.contains(i))
      val partialSucc = state.getInstructionFile(InstructionFile.PartialSuccess)

      if (instruction.isDefined) {
        val instr = instruction.get
        if (goal.contains(instr)) {
          return mkInitialSearch(instr, pseudoTime)
        } else if (partialSucc.contains(instr)) {
          return mkSecondarySearch(instr, pseudoTime)
        } else {
          return None
        }
      }

      // first deal with partial successes (so that we can put them into success as soon as possible)
      if (partialSucc.nonEmpty) {
        val instr = partialSucc(Random.nextInt(partialSucc.size))
        return mkSecondarySearch(instr, pseudoTime)
      }

      // then try an intial search
      if (goal.nonEmpty) {
        if (isImm8Exp) {
          // avoid doing the same instruction over and over
          var notTried = goal.filter(x => !alreadySeen.contains(x))
          if (notTried.isEmpty) {
            alreadySeen.clear()
            notTried = goal
          }
          val instr = notTried(Random.nextInt(notTried.size))
          alreadySeen.add(instr)
          return mkInitialSearch(instr, pseudoTime)
        }
        val instr = goal(Random.nextInt(goal.size))
        return mkInitialSearch(instr, pseudoTime)
      }

      // cannot do anything for now
      None
    })
  }

  private val blacklist = Vector(
    Instruction("vzeroupper"),
    Instruction("divw_r16"),
    Instruction("divl_r32"),
    Instruction("divq_r64"),
    Instruction("divb_r8"),
    Instruction("divb_rh"),
    Instruction("idivw_r16"),
    Instruction("idivl_r32"),
    Instruction("idivq_r64"),
    Instruction("idivb_r8"),
    Instruction("idivb_rh")
  )
}

/**
 * Companion object
 */
object Driver {
  def apply(options: InitOptions) = new Driver(options)
}
