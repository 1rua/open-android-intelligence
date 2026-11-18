package com.openandroidintelligence.conversation.remote

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionModelsTest {
    private val request = SessionModelRequest(remoteOwner(), 1)
    private val firstModel = ModelKey("model-a", providerId = "provider-a")
    private val secondModel = ModelKey("model-b", providerId = "provider-a")

    @Test
    fun emptyLoadedCatalogIsDistinctFromNotLoadedAndAddsNoFallbackModels() {
        val pending: SessionModelDirectory = SessionModelDirectory.NotLoaded(request)
        val empty = SessionModelDirectory.Loaded(request, emptyList())

        assertNotEquals(pending, empty)
        assertTrue(empty.models.isEmpty())
        assertNull(empty.confirmedModel)
        assertNull(empty.efforts)
    }

    @Test
    fun lateModelDirectoriesCannotReplaceAnotherRequestOrConnection() {
        val directory = SessionModelDirectory.Loaded(request, listOf(SessionModelOption(firstModel, "模型 A")))

        assertTrue(directory.isCurrent(request.copy()))
        assertFalse(directory.isCurrent(request.copy(sequence = 2)))
        assertFalse(directory.isCurrent(request.copy(owner = request.owner.copy(connectionGeneration = 3))))
        assertFalse(directory.isCurrent(request.copy(owner = request.owner.copy(
            session = request.owner.session.copy(endpoint = remoteEndpoint().copy(backendId = BackendId("codex"))),
        ))))
    }

    @Test
    fun modelConfirmationMustPrecedeEffortCatalogAdoptionAndInvalidatePreviousModelEfforts() {
        val efforts = ConfirmedModelEfforts(firstModel, listOf(ReasoningEffortOption("deep", "深入")), "deep")
        val confirmed = SessionModelDirectory.Loaded(request,
            models = listOf(SessionModelOption(firstModel, "模型 A"), SessionModelOption(secondModel, "模型 B")),
            confirmedModel = firstModel, efforts = efforts)

        assertEquals(firstModel, confirmed.efforts?.model)
        assertFailsWith<IllegalArgumentException> {
            SessionModelDirectory.Loaded(request, confirmed.models, confirmedModel = secondModel, efforts = efforts)
        }
        assertFailsWith<IllegalArgumentException> {
            SessionModelDirectory.Loaded(request, confirmed.models, efforts = efforts)
        }
        val changed = SessionModelDirectory.Loaded(request, confirmed.models, confirmedModel = secondModel)
        assertNull(changed.efforts)
        val emptyEfforts = SessionModelDirectory.Loaded(request, confirmed.models, confirmedModel = secondModel,
            efforts = ConfirmedModelEfforts(secondModel, emptyList()))
        assertTrue(emptyEfforts.efforts!!.options.isEmpty())
    }

    @Test
    fun providerCoordinatesRemainDistinctEvenWhenModelNamesMatch() {
        val first = SessionModelOption(firstModel, "同名模型")
        val other = SessionModelOption(firstModel.copy(providerId = "provider-b"), "同名模型")
        assertEquals(2, SessionModelDirectory.Loaded(request, listOf(first, other)).models.size)
        assertFailsWith<IllegalArgumentException> { SessionModelDirectory.Loaded(request, listOf(first, first)) }
    }

    @Test
    fun realCurrentModelDoesNotInventASelectableOptionWhenHostCatalogIsEmpty() {
        val state = SessionModelDirectory.Loaded(request, models = emptyList(), confirmedModel = firstModel)
        assertEquals(firstModel, state.confirmedModel)
        assertTrue(state.models.isEmpty())
    }

    @Test
    fun acpModelsWithoutProviderKeepTheirNativeIdentityWithoutFabricatedProvider() {
        val model = ModelKey("native:model-a")
        val directory = SessionModelDirectory.Loaded(request,
            listOf(SessionModelOption(model, "Host 提供的模型")), confirmedModel = model)
        assertEquals("native:model-a", directory.confirmedModel!!.modelId)
        assertNull(directory.confirmedModel.providerId)
        assertEquals(model, directory.models.single().key)
    }

    @Test
    fun workspaceUploadDoesNotImplyImageInputAndExpiredCapabilitiesCannotEnableActions() {
        val directory = SessionCapabilityDirectory(request.owner, setOf(SessionCapability.WORKSPACE_UPLOAD))
        assertTrue(directory.supports(SessionCapability.WORKSPACE_UPLOAD, request.owner))
        assertFalse(directory.supports(SessionCapability.IMAGE_INPUT, request.owner))
        assertFalse(directory.supports(SessionCapability.WORKSPACE_UPLOAD, request.owner.copy(connectionGeneration = 3)))
        assertFalse(SessionCapabilityDirectory(request.owner, emptySet()).supports(SessionCapability.SEND, request.owner))
    }

    @Test
    fun laterDiscoveryBufferChangesCannotRewriteConfirmedModelsEffortsOrCapabilities() {
        val models = mutableListOf(SessionModelOption(firstModel, "模型 A"))
        val efforts = mutableListOf(ReasoningEffortOption("deep", "深入"))
        val supported = mutableSetOf(SessionCapability.WORKSPACE_UPLOAD)
        val directory = SessionModelDirectory.Loaded(request, models, firstModel,
            ConfirmedModelEfforts(firstModel, efforts, "deep"))
        val capabilities = SessionCapabilityDirectory(request.owner, supported)

        models.clear()
        efforts.clear()
        supported += SessionCapability.IMAGE_INPUT

        assertEquals(listOf(firstModel), directory.models.map { it.key })
        assertEquals(listOf("deep"), directory.efforts!!.options.map { it.id })
        assertFalse(capabilities.supports(SessionCapability.IMAGE_INPUT, request.owner))
        assertFailsWith<UnsupportedOperationException> {
            (directory.models as MutableList).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (capabilities.supported as MutableSet).add(SessionCapability.SEND)
        }
    }
}
