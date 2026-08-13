package com.camswitch

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Fusionne plusieurs fichiers MP4 en un seul en utilisant MediaMuxer natif Android.
 * Fonctionne sans aucune dépendance externe.
 */
object VideoMerger {

    private const val TAG = "VideoMerger"

    fun merge(inputFiles: List<File>, outputFile: File): Boolean {
        if (inputFiles.isEmpty()) return false
        if (inputFiles.size == 1) {
            inputFiles[0].copyTo(outputFile, overwrite = true)
            return true
        }

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var videoTimeOffset = 0L
        var audioTimeOffset = 0L
        var lastVideoTime = 0L
        var lastAudioTime = 0L
        var muxerStarted = false

        try {
            val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB buffer
            val bufferInfo = MediaCodec.BufferInfo()

            for (inputFile in inputFiles) {
                if (!inputFile.exists() || inputFile.length() == 0L) {
                    Log.w(TAG, "Fichier vide ou manquant : ${inputFile.name}")
                    continue
                }

                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(inputFile.absolutePath)

                    var fileVideoTrack = -1
                    var fileAudioTrack = -1

                    // Trouver les pistes vidéo et audio
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        when {
                            mime.startsWith("video/") && fileVideoTrack == -1 -> fileVideoTrack = i
                            mime.startsWith("audio/") && fileAudioTrack == -1 -> fileAudioTrack = i
                        }
                    }

                    // Initialiser le muxer avec les formats du premier fichier
                    if (!muxerStarted) {
                        if (fileVideoTrack >= 0) {
                            val fmt = extractor.getTrackFormat(fileVideoTrack)
                            videoTrackIndex = muxer.addTrack(fmt)
                        }
                        if (fileAudioTrack >= 0) {
                            val fmt = extractor.getTrackFormat(fileAudioTrack)
                            audioTrackIndex = muxer.addTrack(fmt)
                        }
                        muxer.start()
                        muxerStarted = true
                    }

                    var fileDurationVideo = 0L
                    var fileDurationAudio = 0L

                    // Copier la piste vidéo
                    if (fileVideoTrack >= 0 && videoTrackIndex >= 0) {
                        extractor.selectTrack(fileVideoTrack)
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                        while (true) {
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) break

                            bufferInfo.offset = 0
                            bufferInfo.size = size
                            bufferInfo.presentationTimeUs = extractor.sampleTime + videoTimeOffset
                            bufferInfo.flags = extractor.sampleFlags

                            muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)

                            fileDurationVideo = maxOf(fileDurationVideo, extractor.sampleTime)
                            lastVideoTime = bufferInfo.presentationTimeUs
                            extractor.advance()
                        }
                        extractor.unselectTrack(fileVideoTrack)
                    }

                    // Copier la piste audio
                    if (fileAudioTrack >= 0 && audioTrackIndex >= 0) {
                        extractor.selectTrack(fileAudioTrack)
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                        while (true) {
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) break

                            bufferInfo.offset = 0
                            bufferInfo.size = size
                            bufferInfo.presentationTimeUs = extractor.sampleTime + audioTimeOffset
                            bufferInfo.flags = extractor.sampleFlags

                            muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)

                            fileDurationAudio = maxOf(fileDurationAudio, extractor.sampleTime)
                            lastAudioTime = bufferInfo.presentationTimeUs
                            extractor.advance()
                        }
                        extractor.unselectTrack(fileAudioTrack)
                    }

                    // Calculer l'offset pour le fichier suivant
                    videoTimeOffset = lastVideoTime + 33333L  // +1 frame à 30fps
                    audioTimeOffset = lastAudioTime + 23220L  // +1 frame audio AAC

                } finally {
                    extractor.release()
                }
            }

            muxer.stop()
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Erreur fusion : ${e.message}", e)
            outputFile.delete()
            return false
        } finally {
            try { muxer.release() } catch (e: Exception) { }
        }
    }
}
