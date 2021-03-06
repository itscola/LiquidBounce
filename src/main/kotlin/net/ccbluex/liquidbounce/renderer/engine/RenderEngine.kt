/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2016 - 2021 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */

package net.ccbluex.liquidbounce.renderer.engine

import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.event.*
import net.ccbluex.liquidbounce.interfaces.IMixinGameRenderer
import net.ccbluex.liquidbounce.renderer.Fonts
import net.ccbluex.liquidbounce.renderer.engine.font.GlyphPage
import net.ccbluex.liquidbounce.utils.Mat4
import net.ccbluex.liquidbounce.utils.toMat4
import net.minecraft.client.MinecraftClient
import org.lwjgl.opengl.GL11
import java.util.concurrent.LinkedBlockingQueue
import java.util.regex.Pattern

class Layer(val renderTasks: ArrayList<RenderTask> = ArrayList(200))
class LayerSettings(val mvpMatrix: Mat4, val culling: Boolean)

/**
 * Handles all rendering tasks.
 *
 * The tasks are grouped by layer, higher layers are rendered on top of smaller ones
 */
object RenderEngine : Listenable {
    /**
     * Uses the perspective of the minecraft camera; Coordinates match minecraft coordinates, culling enabled
     */
    const val CAMERA_VIEW_LAYER = 0

    /**
     * Similar to [CAMERA_VIEW_LAYER]. Used for 2D-ESP, Nametags, etc., backface culling enabled
     */
    const val PSEUDO_2D_LAYER = 1

    /**
     * Projects the vertices on the screen. 1 unit = 1 px, backface culling enabled
     */
    const val HUD_LAYER = 2

    /**
     * How many layers is the render engine supposed to render?
     */
    private const val LAYER_CONT = 5

    /**
     * The table the tasks are stored it, grouped by layers
     */
    val renderTaskTable: Array<Layer> = Array(LAYER_CONT) { Layer() }

    /**
     * Contains runnables with tasks to run when the render engine ticks the next time
     */
    val deferredForRenderThread: LinkedBlockingQueue<Runnable> = LinkedBlockingQueue()

    /**
     * What OpenGL level is this client supposed to use? Determined when initialized
     */
    var openglLevel: OpenGLLevel = OpenGLLevel.OpenGL1_2

    /**
     * Used to recognize what GL version we are on
     */
    val openGlVersionRegex = Pattern.compile("(\\d+)\\.(\\d+)(\\.(\\d+))?(.*)")

    val renderHandler = handler<RenderHudEvent> {
        EventManager.callEvent(LiquidBounceRenderEvent(it.tickDelta))

        render(it.tickDelta)

        // Run the deferred tasks
        while (true) {
            val currentTask = deferredForRenderThread.poll() ?: break

            currentTask.run()
        }
    }

    /**
     * Initialization
     */
    fun init() {
        Shaders.init()
        GlyphPage.init()
        Fonts.loadFonts()

        val versionString = GL11.glGetString(GL11.GL_VERSION)

        if (versionString == null) {
            LiquidBounce.logger.error("OpenGL didn't return a version string.")

            return
        }

        val matcher = openGlVersionRegex.matcher(versionString)

        if (!matcher.matches()) {
            LiquidBounce.logger.error("OpenGL returned an invalid version string: $versionString")

            return
        }

        val majorVersion = matcher.group(1).toInt()
        val minorVersion = matcher.group(2).toInt()
        val patchVersion = if (matcher.groupCount() >= 5) matcher.group(4).toInt() else null

        openglLevel = OpenGLLevel.getBestLevelFor(majorVersion, minorVersion)

        println("Found out OpenGL version to be $majorVersion.$minorVersion${if (patchVersion != null) ".$patchVersion" else ""}. Using backend for ${openglLevel.backendInfo}")
    }

    /**
     * Enqueues a task for rendering
     *
     * @param layer The layer it is suppose to be rendered on (See this class's description)
     */
    fun enqueueForRendering(layer: Int, task: RenderTask) {
        this.renderTaskTable[layer].renderTasks.add(task)
    }

    /**
     * @see enqueueForRendering
     */
    fun enqueueForRendering(layer: Int, task: Array<RenderTask>) {
        this.renderTaskTable[layer].renderTasks.addAll(task)
    }

    /**
     * Runs the [runnable] on the next rendering tick. This can be used to run something on a
     * thread with OpenGL context
     *
     * Thread safe.
     */
    fun runOnGlContext(runnable: Runnable) {
        this.deferredForRenderThread.add(runnable)
    }

    /**
     * Draws all enqueued render tasks.
     */
    fun render(tickDelta: Float) {
        val lvl = this.openglLevel

        GL11.glEnable(GL11.GL_ALPHA_TEST)
        GL11.glEnable(GL11.GL_BLEND)

        for ((idx, layer) in renderTaskTable.withIndex()) {
            // Don't calculate mvp matrices for empty layers
            if (layer.renderTasks.isEmpty())
                continue

            val settings = getSettingsForLayer(idx, tickDelta)
//
            for (renderTask in layer.renderTasks) {
                renderTask.initRendering(lvl, settings.mvpMatrix)
                renderTask.draw(lvl)
                renderTask.cleanupRendering(lvl)
            }

            layer.renderTasks.clear()
        }
    }

    fun getSettingsForLayer(layer: Int, tickDelta: Float): LayerSettings {
        return when (layer) {
            CAMERA_VIEW_LAYER -> LayerSettings(
                (MinecraftClient.getInstance().gameRenderer as IMixinGameRenderer).getCameraMVPMatrix(
                    false,
                    tickDelta
                ).toMat4(), true
            )
            PSEUDO_2D_LAYER -> LayerSettings(
                (MinecraftClient.getInstance().gameRenderer as IMixinGameRenderer).getCameraMVPMatrix(
                    false,
                    tickDelta
                ).toMat4(), true
            )
            HUD_LAYER -> LayerSettings(
                Mat4.projectionMatrix(
                    0.0f,
                    0.0f,
                    MinecraftClient.getInstance().window.framebufferWidth.toFloat(),
                    MinecraftClient.getInstance().window.framebufferHeight.toFloat(),
                    -1.0f,
                    1.0f
                ),
                true
            )
            else -> throw UnsupportedOperationException("Unknown layer")
        }
    }

    override fun handleEvents(): Boolean = true
}
