/*
 * Jigasi, the JItsi GAteway to SIP.
 *
 * Copyright @ 2017 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.jigasi.transcription;

import net.java.sip.communicator.service.protocol.*;
import org.jitsi.impl.neomedia.device.*;
import org.jitsi.jigasi.transcription.action.*;
import org.jitsi.util.*;

import javax.media.Buffer;
import javax.media.rtp.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * A transcriber object which will keep track of participants in a conference
 * which will need to be transcribed. It will manage starting and stopping
 * of said transcription as well as providing the transcription
 *
 * @author Nik Vaessen
 */
public class Transcriber
    implements ReceiveStreamBufferListener
{
    /**
     * The logger of this class
     */
    private final static Logger logger = Logger.getLogger(Transcriber.class);

    /**
     * The states the transcriber can be in. The Transcriber
     * can only go through one cycle. So once it is started it can never
     * be started, and once is is stopped it can never be stopped and once
     * it is finished it will not be able to start again
     */
    private enum State
    {
        /**
         * when transcription has not started
         */
        NOT_STARTED,

        /**
         * when actively transcribing or when stopped but still transcribing
         * audio not yet handled
         */
        TRANSCRIBING,

        /**
         * when not accepting new audio but still transcribing audio already
         * buffered or sent
         */
        FINISHING_UP,

        /**
         * when finishing last transcriptions and no new results will ever
         * come in
         */
        FINISHED
    }

    /**
     * The current state of the transcribing
     */
    private State state = State.NOT_STARTED;

    /**
     * Holds participants of the conference which need
     * to be transcribed
     */
    private Map<Long, Participant> participants = new HashMap<>();

    /**
     * The object which will hold the actual transcription
     * and which will be continuously updated as newly transcribed
     * audio comes in
     */
    private Transcript transcript = new Transcript();

    /**
     * The MediaDevice which will get all audio to transcribe
     */
    private TranscribingAudioMixerMediaDevice mediaDevice
        = new TranscribingAudioMixerMediaDevice(this);

    /**
     * Every listener which will be notified when a new result comes in
     * or the transcription has been completed
     */
    private ArrayList<TranscriptionListener> listeners = new ArrayList<>();

    /**
     * Every listener which will be notified when a new <tt>TranscriptEvent</tt>
     * is created.
     */
    private ArrayList<TranscriptionEventListener> transcriptionEventListeners
        = new ArrayList<>();

    /**
     * The service which is used to send audio and receive the
     * transcription of said audio
     */
    private TranscriptionService transcriptionService;

    /**
     * A single thread which is used to manage the buffering and sending
     * of audio packets. This is used to offload work from the thread dealing
     * with all packets, which only has 20 ms before new packets come in.
     * <p>
     * Will be created in {@link Transcriber#start()} and shutdown in
     * {@link Transcriber#stop()}
     */
    ExecutorService executorService;

    /**
     * The name of the room of the conference which will be transcribed
     */
    private String roomName;

    /**
     * Create a transcription object which can be used to add and remove
     * participants of a conference to a list of audio streams which will
     * be transcribed.
     *
     * @param roomName the roomanem the transcription will take place in
     * @param service the transcription service which will be used to transcribe
     * the audio streams
     */
    public Transcriber(String roomName, TranscriptionService service)
    {
        if (!service.supportsStreamRecognition())
        {
            throw new IllegalArgumentException(
                    "Currently only services which support streaming "
                    + "recognition are supported");
        }
        this.transcriptionService = service;
        addTranscriptionListener(this.transcript);
        this.roomName = roomName;
    }


    /**
     * Create a transcription object which can be used to add and remove
     * participants of a conference to a list of audio streams which will
     * be transcribed.
     *
     * @param service the transcription service which will be used to transcribe
     * the audio streams
     */
    public Transcriber(TranscriptionService service)
    {
        this(null, service);
    }

    /**
     * Add a participant to the list of participants being transcribed
     *
     * @param chatMember the chat participant to be added
     * @param ssrc the ssrc of the participant to be added
     */
    public void add(ChatRoomMember chatMember, long ssrc)
    {
        Participant participant;
        if (this.participants.containsKey(ssrc))
        {
            participant = this.participants.get(ssrc);
        }
        else
        {
            participant = new Participant(this, chatMember, ssrc);
            this.participants.put(ssrc, participant);
        }

        participant.joined();
        TranscriptEvent event = transcript.notifyJoined(participant);
        if (event != null)
        {
            fireTranscribeEvent(event);
        }

        if (logger.isDebugEnabled())
            logger.debug("Added participant " + chatMember.getDisplayName()
                + " with ssrc " + ssrc);
    }

    /**
     * Remove a participant from the list of participants being transcribed
     *
     * @param chatMember the chat participant to be removed
     * @param ssrc the ssrc of the participant to be removed
     */
    public void remove(ChatRoomMember chatMember, long ssrc)
    {
        if (this.participants.containsKey(ssrc))
        {
            Participant participant =  participants.get(ssrc);
            participant.left();
            TranscriptEvent event = transcript.notifyLeft(participant);
            if (event != null)
            {
                fireTranscribeEvent(event);
            }

            if (logger.isDebugEnabled())
                logger.debug(
                    "Removed participant " + chatMember.getDisplayName()
                        + " with ssrc " + ssrc);

            return;
        }

        logger.warn("Asked to remove participant" + chatMember.getDisplayName()
            + " with ssrc " + ssrc + " which did not exist");
    }

    /**
     * Start transcribing all participants added to the list
     */
    public void start()
    {
        if (State.NOT_STARTED.equals(this.state))
        {
            if (logger.isDebugEnabled())
                logger.debug("Transcriber is now transcribing");

            this.state = State.TRANSCRIBING;
            this.executorService = Executors.newSingleThreadExecutor();

            List<Participant> participantsClone
                = new ArrayList<>(this.participants.size());

            participantsClone.addAll(this.participants.values());

            TranscriptEvent event
                = this.transcript.started(roomName, participantsClone);
            if (event != null)
            {
                fireTranscribeEvent(event);
            }
        }
        else
        {
            logger.warn("Trying to start Transcriber while it is" +
                            "already started");
        }
    }

    /**
     * Stop transcribing all participants added to the list
     */
    public void stop()
    {
        if (State.TRANSCRIBING.equals(this.state))
        {
            if (logger.isDebugEnabled())
                logger.debug("Transcriber is now finishing up");

            this.state = State.FINISHING_UP;
            this.executorService.shutdown();

            TranscriptEvent event = this.transcript.ended();
            fireTranscribeEvent(event);
            ActionServicesHandler.getInstance()
                .notifyActionServices(this, event);

            checkIfFinishedUp();
        }
        else
        {
            logger.warn("Trying to stop Transcriber while it is" +
                            "already stopped");
        }
    }

    /**
     * Transcribing will stop, last chance to post something to the room.
     */
    public void willStop()
    {
        if (State.TRANSCRIBING.equals(this.state))
        {
            TranscriptEvent event = this.transcript.willEnd();
            fireTranscribeEvent(event);
            ActionServicesHandler.getInstance()
                .notifyActionServices(this, event);
        }
        else
        {
            logger.warn("Trying to notify Transcriber for a while it is" +
                "already stopped");
        }
    }

    /**
     * Get whether the transcriber has been started
     *
     * @return true when the transcriber has been started false when not yet
     * started or already stopped
     */
    public boolean isTranscribing()
    {
        return State.TRANSCRIBING.equals(this.state);
    }

    /**
     * Get whether the transcribed has been stopped and will not have any new
     * results coming in. This is always true after every
     * {@link TranscriptionListener} has has their
     * {@link TranscriptionListener#completed()} method called
     *
     * @return true when the transcribed has stopped and no new results will
     * ever come in
     */
    public boolean finished()
    {
        return State.FINISHED.equals(this.state);
    }

    /**
     * Get whether the transcribed has been stopped and is processing the
     * last audio fragments before it will be finished
     *
     * @return true when the transcriber is waiting for the last results to come
     * in, false otherwise
     */
    public boolean finshingUp()
    {
        return State.FINISHING_UP.equals(this.state);
    }

    /**
     * Provides the (ongoing) transcription of the conference this object
     * is transcribing
     *
     * @return the Transcript object which will be updated as long as this
     * object keeps transcribing
     */
    public Transcript getTranscript()
    {
        return transcript;
    }

    /**
     * Add a TranscriptionListener which will be notified when the Transcription
     * is updated due to new TranscriptionResult's coming in
     *
     * @param listener the listener which will be notified
     */
    public void addTranscriptionListener(TranscriptionListener listener)
    {
        listeners.add(listener);
    }

    /**
     * Remove a TranscriptionListener such that it will no longer be
     * notified of new results
     *
     * @param listener the listener to remove
     */
    public void removeTranscriptionListener(TranscriptionListener listener)
    {
        listeners.remove(listener);
    }

    /**
     * Add a TranscriptionEventListener which will be notified when
     * the TranscriptionEvent is created.
     *
     * @param listener the listener which will be notified
     */
    public void addTranscriptionEventListener(
        TranscriptionEventListener listener)
    {
        transcriptionEventListeners.add(listener);
    }

    /**
     * Remove a TranscriptionListener such that it will no longer be
     * notified of new results
     *
     * @param listener the listener to remove
     */
    public void removeTranscriptionEventListener(
        TranscriptionEventListener listener)
    {
        transcriptionEventListeners.remove(listener);
    }

    /**
     * The transcriber can be used as a {@link ReceiveStreamBufferListener}
     * to listen for new audio packets coming in through a MediaDevice. It will
     * try to filter them based on the SSRC of the packet. If the SSRC does not
     * match a participant added to the transcribed, an exception will be thrown
     * <p>
     * Note that this code is run in a Thread doing audio mixing and only
     * has 20 ms for each frame
     *
     * @param receiveStream the stream from which the audio was received
     * @param buffer the containing the audio as well as meta-data
     */
    @Override
    public void bufferReceived(ReceiveStream receiveStream, Buffer buffer)
    {
        if (!isTranscribing())
        {
            return;
        }

        long ssrc = receiveStream.getSSRC() & 0xffffffffL;

        Participant p = participants.get(ssrc);
        if (p != null)
        {
            p.giveBuffer(buffer);
        }
        else
        {
            logger.warn("Reading from SSRC " + ssrc + " while it is " +
                            "not known as a participant");
        }
    }

    /**
     * Get the MediaDevice this transcriber is listening to for audio
     *
     * @return the AudioMixerMediaDevice which should receive all audio needed
     * to be transcribed
     */
    public AudioMixerMediaDevice getMediaDevice()
    {
        return this.mediaDevice;
    }

    /**
     * Check if all participants have been completely transcribed. When this
     * is the case, set the state from FINISHING_UP to FINISHED
     */
    void checkIfFinishedUp()
    {
        if (State.FINISHING_UP.equals(this.state))
        {
            for (Participant participant : participants.values())
            {
                if (!participant.isCompleted())
                {
                    return;
                }
            }

            if (logger.isDebugEnabled())
                logger.debug("Transcriber is now finished");

            this.state = State.FINISHED;
            for (TranscriptionListener listener : listeners)
            {
                listener.completed();
            }
        }
    }

    /**
     * @return the {@link TranscriptionService}.
     */
    public TranscriptionService getTranscriptionService()
    {
        return transcriptionService;
    }

    /**
     * Notifies all of the listeners of this {@link Transcriber} of a new
     * {@link TranscriptionResult} which was received.
     *
     * @param result the result.
     */
    void notify(TranscriptionResult result)
    {
        for (TranscriptionListener listener : listeners)
        {
            listener.notify(result);
        }
    }

    /**
     * Returns the name of the room of the conference which will be transcribed.
     * @return the room name.
     */
    public String getRoomName()
    {
        return roomName;
    }

    /**
     * Notifies all <tt>TranscriptionEventListener</tt>s for new
     * <tt>TranscriptEvent</tt>.
     * @param event the new event.
     */
    private void fireTranscribeEvent(TranscriptEvent event)
    {
        for (TranscriptionEventListener listener : transcriptionEventListeners)
        {
            listener.notify(this, event);
        }
    }
}
