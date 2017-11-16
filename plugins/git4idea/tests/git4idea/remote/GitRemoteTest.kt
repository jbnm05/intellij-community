/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.remote

import com.intellij.openapi.components.service
import com.intellij.testFramework.RunAll
import com.intellij.util.ThrowableRunnable
import git4idea.checkout.GitCheckoutProvider
import git4idea.commands.GitHttpAuthService
import git4idea.commands.GitHttpAuthenticator
import git4idea.config.GitVersion
import git4idea.remote.GitRemoteTest.ConfigScope.GLOBAL
import git4idea.remote.GitRemoteTest.ConfigScope.SYSTEM
import git4idea.test.GitHttpAuthTestService
import git4idea.test.GitPlatformTest
import git4idea.test.git
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GitRemoteTest : GitPlatformTest() {

  private lateinit var authenticator : TestAuthenticator
  private lateinit var authTestService : GitHttpAuthTestService
  private lateinit var credentialHelpers: Map<ConfigScope, String>

  private val projectName = "projectA"

  override fun setUp() {
    super.setUp()

    authenticator = TestAuthenticator()
    authTestService = service<GitHttpAuthService>() as GitHttpAuthTestService
    authTestService.register(authenticator)

    credentialHelpers = readAndResetCredentialHelpers()
  }

  override fun tearDown() {
    RunAll()
      .append(ThrowableRunnable { authTestService.cleanup() })
      .append(ThrowableRunnable { restoreCredentialHelpers() })
      .append(ThrowableRunnable { super.tearDown() })
      .run()
  }

  fun `test clone from http with username`() {
    val cloneWaiter = cloneOnPooledThread(makeUrl("gituser"))

    assertPasswordAsked()
    authenticator.supplyPassword("gitpassword")

    assertCloneSuccessful(cloneWaiter)
  }

  fun `test clone from http without username`() {
    val cloneWaiter = cloneOnPooledThread(makeUrl(null))

    assertUsernameAsked()
    authenticator.supplyUsername("gituser")
    assertPasswordAsked()
    authenticator.supplyPassword("gitpassword")

    assertCloneSuccessful(cloneWaiter)
  }

  fun `test clone fails if incorrect password`() {
    val url = makeUrl("gituser")

    val cloneWaiter = cloneOnPooledThread(url)

    assertPasswordAsked()
    authenticator.supplyPassword("incorrect")

    assertTrue("Clone didn't complete during the reasonable period of time", cloneWaiter.await(30, TimeUnit.SECONDS))
    assertFalse("Repository directory shouldn't be created", File(testRoot, projectName).exists())

    val expectedAuthFailureMessage = if (vcs.version.isLaterOrEqual(GitVersion(1, 8, 3, 0))) {
      "Authentication failed for '$url/'"
    }
    else {
      "Authentication failed"
    }
    assertErrorNotification("Clone failed", expectedAuthFailureMessage)
  }

  private fun makeUrl(username: String?) : String {
    val login = if (username == null) "" else "$username@"
    return "http://${login}deb6-vm7-git.labs.intellij.net/$projectName.git"
  }

  private fun cloneOnPooledThread(url: String): CountDownLatch {
    val cloneWaiter = CountDownLatch(1)
    executeOnPooledThread {
      val projectName = url.substring(url.lastIndexOf('/') + 1).replace(".git", "")
      GitCheckoutProvider.doClone(project, git, projectName, testRoot.path, url)
      cloneWaiter.countDown()
    }
    return cloneWaiter
  }

  private fun readAndResetCredentialHelpers(): Map<ConfigScope, String> {
    val system = readAndResetCredentialHelper(SYSTEM)
    val global = readAndResetCredentialHelper(GLOBAL)
    return mapOf(SYSTEM to system, GLOBAL to global)
  }

  private fun readAndResetCredentialHelper(scope: ConfigScope): String {
    val value = git("config ${scope.param()} --get-all credential.helper", true)
    git("config ${scope.param()} --unset-all credential.helper", true)
    return value
  }

  private fun restoreCredentialHelpers() {
    credentialHelpers.forEach { scope, value ->
      if (value.isNotBlank()) git("config ${scope.param()} credential.helper ${value}", true)
    }
  }

  private fun assertCloneSuccessful(cloneCompleted: CountDownLatch) {
    assertTrue("Clone didn't complete during the reasonable period of time", cloneCompleted.await(30, TimeUnit.SECONDS))
    assertTrue("Repository directory was not found", File(testRoot, projectName).exists())
  }

  private fun assertPasswordAsked() {
    authenticator.waitUntilPasswordIsAsked()
    assertTrue("Password was not requested", authenticator.wasPasswordAsked())
  }

  private fun assertUsernameAsked() {
    authenticator.waitUntilUsernameIsAsked()
    assertTrue("Username was not requested", authenticator.wasUsernameAsked())
  }

  private class TestAuthenticator : GitHttpAuthenticator {
    private val TIMEOUT = 10

    private val passwordAskedWaiter = CountDownLatch(1)
    private val usernameAskedWaiter = CountDownLatch(1)
    private val passwordSuppliedWaiter = CountDownLatch(1)
    private val usernameSuppliedWaiter = CountDownLatch(1)

    @Volatile private var passwordAsked: Boolean = false
    @Volatile private var usernameAsked: Boolean = false

    @Volatile private lateinit var password: String
    @Volatile private lateinit var username: String

    override fun askPassword(url: String): String {
      passwordAsked = true
      passwordAskedWaiter.countDown()
      assertTrue("Password was not supplied during the reasonable period of time",
                 passwordSuppliedWaiter.await(TIMEOUT.toLong(), TimeUnit.SECONDS))
      return password
    }

    override fun askUsername(url: String): String {
      usernameAsked = true
      usernameAskedWaiter.countDown()
      assertTrue("Password was not supplied during the reasonable period of time",
                 usernameSuppliedWaiter.await(TIMEOUT.toLong(), TimeUnit.SECONDS))
      return username
    }


    internal fun supplyPassword(password: String) {
      this.password = password
      passwordSuppliedWaiter.countDown()
    }

    internal fun supplyUsername(username: String) {
      this.username = username
      usernameSuppliedWaiter.countDown()
    }

    internal fun waitUntilPasswordIsAsked() {
      assertTrue("Password was not asked during the reasonable period of time",
                 passwordAskedWaiter.await(TIMEOUT.toLong(), TimeUnit.SECONDS))
    }

    internal fun waitUntilUsernameIsAsked() {
      assertTrue("Username was not asked during the reasonable period of time",
                 usernameAskedWaiter.await(TIMEOUT.toLong(), TimeUnit.SECONDS))
    }

    override fun saveAuthData() {}

    override fun forgetPassword() {}

    override fun wasCancelled(): Boolean {
      return false
    }

    internal fun wasPasswordAsked(): Boolean {
      return passwordAsked
    }

    internal fun wasUsernameAsked(): Boolean {
      return usernameAsked
    }
  }

  private enum class ConfigScope {
    SYSTEM,
    GLOBAL;

    fun param() = "--${name.toLowerCase()}"
  }
}