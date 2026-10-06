package network.libertychat.app.test

import network.libertychat.app.data.model.TcpCommunityServer
import network.libertychat.app.viewmodel.TcpClientWizardState
import network.libertychat.app.viewmodel.TcpClientWizardStep

/**
 * Test fixtures for TCP Client Wizard UI tests.
 */
object TcpClientWizardTestFixtures {
    val testServer =
        TcpCommunityServer(
            name = "Test Server",
            host = "test.example.com",
            port = 4242,
        )

    val anotherTestServer =
        TcpCommunityServer(
            name = "Another Server",
            host = "another.example.com",
            port = 5000,
        )

    /** LCS: a server carrying the optional picker note, to cover that rendering path. */
    val notedTestServer =
        TcpCommunityServer(
            name = "Noted Server",
            host = "noted.example.com",
            port = 4246,
            note = "Use this one when the other is unreachable",
        )

    val testServers = listOf(testServer, anotherTestServer)

    val testServersWithNote = listOf(testServer, notedTestServer)

    // Convenience methods for common state configurations
    fun serverSelectionState(
        selectedServer: TcpCommunityServer? = null,
        isCustomMode: Boolean = false,
    ) = TcpClientWizardState(
        currentStep = TcpClientWizardStep.SERVER_SELECTION,
        selectedServer = selectedServer,
        isCustomMode = isCustomMode,
    )

    fun reviewConfigureState(
        selectedServer: TcpCommunityServer? = testServer,
        isCustomMode: Boolean = false,
        interfaceName: String = selectedServer?.name.orEmpty(),
        targetHost: String = selectedServer?.host.orEmpty(),
        targetPort: String = selectedServer?.port?.toString().orEmpty(),
    ) = TcpClientWizardState(
        currentStep = TcpClientWizardStep.REVIEW_CONFIGURE,
        selectedServer = selectedServer,
        isCustomMode = isCustomMode,
        interfaceName = interfaceName,
        targetHost = targetHost,
        targetPort = targetPort,
    )

    fun customModeReviewState(
        interfaceName: String = "",
        targetHost: String = "",
        targetPort: String = "",
    ) = TcpClientWizardState(
        currentStep = TcpClientWizardStep.REVIEW_CONFIGURE,
        selectedServer = null,
        isCustomMode = true,
        interfaceName = interfaceName,
        targetHost = targetHost,
        targetPort = targetPort,
    )

    fun savingState() =
        TcpClientWizardState(
            currentStep = TcpClientWizardStep.REVIEW_CONFIGURE,
            selectedServer = testServer,
            interfaceName = testServer.name,
            targetHost = testServer.host,
            targetPort = testServer.port.toString(),
            isSaving = true,
        )

    fun errorState(errorMessage: String = "Test error") =
        TcpClientWizardState(
            currentStep = TcpClientWizardStep.REVIEW_CONFIGURE,
            selectedServer = testServer,
            interfaceName = testServer.name,
            targetHost = testServer.host,
            targetPort = testServer.port.toString(),
            saveError = errorMessage,
        )

    fun successState() =
        TcpClientWizardState(
            currentStep = TcpClientWizardStep.REVIEW_CONFIGURE,
            selectedServer = testServer,
            interfaceName = testServer.name,
            targetHost = testServer.host,
            targetPort = testServer.port.toString(),
            saveSuccess = true,
        )
}
