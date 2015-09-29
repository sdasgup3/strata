package denali.util

import java.io.File

/**
 * Class to do file-based locking.
 *
 * All coordination happens through files in denali, as to make it easy to run a single experiment on multiple
 * machines. The convention is to use a file '.dir.lock' to indicate locking of a directory, and to use a file
 * '.name.ext.lock' to lock the file 'name.ext'.  The lock file contains the process and thread ID.
 */
object Locking {

  /** Lock a given file.  Blocks until the lock is available. */
  def lockFile(file: File): Unit = {
    lockImpl(getLockNameForFile(file))
  }

  /** Unlock a file. */
  def unlockFile(file: File): Unit = {
    unlockImpl(getLockNameForFile(file))
  }

  /** Lock a given directory.  Blocks until the lock is available. */
  def lockDir(file: File): Unit = {
    lockImpl(getLockNameForDir(file))
  }

  /** Unlock a directory. */
  def unlockDir(file: File): Unit = {
    unlockImpl(getLockNameForDir(file))
  }

  private def lockImpl(lock: File): Unit = {
    while (!lock.createNewFile()) {
      Thread.sleep(50)
    }
    lock.deleteOnExit()
  }

  private def unlockImpl(lock: File): Unit = {
    assert(lock.exists)
    val res = lock.delete()
    assert(res)
  }

  private def getLockNameForFile(file: File): File = {
    new File(s"${file.getParentFile.getPath}/.${file.getName}.lock")
  }

  private def getLockNameForDir(file: File): File = {
    new File(s"${file.getPath}/.dir.lock")
  }
}
