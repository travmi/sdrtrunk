/*******************************************************************************
 *     SDR Trunk 
 *     Copyright (C) 2014 Dennis Sheirer
 * 
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 * 
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 * 
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 ******************************************************************************/
package controller.channel;

import gui.SDRTrunk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import message.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import record.Recorder;
import record.wave.ComplexWaveRecorder;
import record.wave.FloatWaveRecorder;
import sample.Broadcaster;
import sample.Listener;
import sample.complex.ComplexSample;
import sample.simplex.SimplexBuffer;
import sample.simplex.SimplexSampleListener;
import source.ComplexSource;
import source.SimplexSource;
import source.Source;
import source.SourceException;
import source.config.SourceConfigMixer;
import source.tuner.Tuner;
import source.tuner.TunerChannelSource;
import alias.AliasList;
import audio.AudioOutput;
import audio.AudioTypeListener;
import audio.SquelchListener;
import controller.ResourceManager;
import controller.ThreadPoolManager.ThreadType;
import controller.activity.CallEvent;
import controller.channel.Channel.ChannelType;
import controller.state.ChannelState;
import controller.state.ChannelState.State;
import decode.Decoder;
import decode.DecoderFactory;
import dsp.afc.AutomaticFrequencyControl;
import eventlog.EventLogType;
import eventlog.EventLogger;

/**
 * A channel has all of the pieces needed to wire together a source, decoder,
 * event logger and recorder and be started and stopped.
 */
public class ProcessingChain implements Listener<Message>
{
	private final static Logger mLog = LoggerFactory.getLogger( SDRTrunk.class );

	protected AtomicBoolean mRunning = new AtomicBoolean();
    protected Channel mChannel;
	protected Source mSource;
	protected Decoder mDecoder;
	protected List<Recorder> mRecorders = new ArrayList<Recorder>();
	protected ArrayList<EventLogger> mEventLoggers = new ArrayList<EventLogger>();
	protected AudioOutput mAudioOutput;
	protected AutomaticFrequencyControl mAFC;
	protected ChannelState mChannelState;
	protected ResourceManager mResourceManager;
	private ScheduledFuture<?> mProcessorTask;
	private AliasList mAliasList;

	private ComplexSampleReceiver mComplexReceiver = new ComplexSampleReceiver();
	private LinkedTransferQueue<List<ComplexSample>> mComplexQueue = 
								new LinkedTransferQueue<List<ComplexSample>>();

	private SimplexSampleReceiver mSimplexReceiver = new SimplexSampleReceiver();
	private LinkedTransferQueue<SimplexBuffer> mSimplexQueue = 
									new LinkedTransferQueue<SimplexBuffer>();

	protected Broadcaster<Message> mBroadcaster = new Broadcaster<Message>();

	public ProcessingChain( Channel channel, ResourceManager resourceManager )
	{
		mChannel = channel;
		mResourceManager = resourceManager;
		
		if( mResourceManager != null )
		{
			mAliasList = mResourceManager.getPlaylistManager()
					.getPlayist().getAliasDirectory().getAliasList(  
							mChannel.getAliasListName() );
		}
	}

	/**
	 * Automatic Frequency Control, or null if disabled
	 */
	public AutomaticFrequencyControl getAFC()
	{
		return mAFC;
	}
	
	public Listener<List<ComplexSample>> getComplexReceiver()
	{
		return mComplexReceiver;
	}
	
	public Listener<SimplexBuffer> getSimplexReceiver()
	{
		return (Listener<SimplexBuffer>)mSimplexReceiver;
	}
	
	public String toString()
	{
		return mChannel.getName();
	}
	
	public AliasList getAliasList()
	{
		return mAliasList;
	}
	
	public void dispose()
	{
		if( mProcessorTask != null )
		{
			mResourceManager.getThreadPoolManager().cancel( mProcessorTask );
		}
		
		if( mChannelState != null )
		{
			mChannelState.dispose();
		}
		
		mChannelState = null;

		if( mDecoder != null )
		{
			mDecoder.dispose();
		}

		mBroadcaster.dispose();
		mEventLoggers.clear();
		mRecorders.clear();
		

		if( mAudioOutput != null )
		{
			mAudioOutput.dispose();
		}
		
		mResourceManager = null;
	}
	
	public ResourceManager getResourceManager()
	{
		return mResourceManager;
	}
	
    public void start()
    {
		if( !mRunning.get() && mChannel.getEnabled() )
		{
			updateSource();

			updateDecoder();
			
			updateEventLogging();
			
			updateRecording();

			mRunning.set( true );
		}
		else
		{
			mLog.info( getLogPrefix() + "start() invoked, but processing chain is "
					+ "already started" );
		}
    }

	/**
	 * Performs closeout operations to prepare for channel deletion
	 */
	public void stop()
	{
		if( mRunning.get() )
		{
			updateSource();
			
			updateDecoder();
				
			updateRecording();

			/* Flush call events to the logger */
			if( getChannel().getChannelType() == ChannelType.STANDARD )
			{
				getChannelState().getCallEventModel().flush();
			}

			updateEventLogging();
			
			if( mAudioOutput != null )
			{
				mAudioOutput.setAudioPlaybackEnabled( false );
			}

			mRunning.set( false );
		}
		else
		{
			mLog.info( getLogPrefix() + "stop() invoked, but processing chain was "
					+ "already stopped" );
		}
	}

	private String getLogPrefix()
	{
		return "Channel [" + mChannel.getName() + "] - ";
	}
	
	public void updateSource()
	{
		/* Cleanup existing source */
		if( mSource != null )
		{
			if( mSource instanceof ComplexSource )
			{
				((ComplexSource)mSource).removeListener( this.getComplexReceiver() );
			}
			else if( mSource instanceof SimplexSource )
			{
				((SimplexSource)mSource)
					.removeListener( this.getSimplexReceiver() );
			}
			
			if( mProcessorTask != null )
			{
				mResourceManager.getThreadPoolManager().cancel( mProcessorTask );
			}
			
			mSource.dispose();
			
			mSource = null;
		}
		
		/* Get a new source if needed and start the sample processor thread */
		if( mChannel.getEnabled() && mResourceManager != null )
		{
			try
            {
	            mSource = mResourceManager.getSourceManager()
	            					.getSource( ProcessingChain.this );
            }
            catch ( SourceException e )
            {
            	mLog.error( "Couldn't obtain source for processing chain [" + 
            					toString() + "]", e );
            	
            	mSource = null;
            }

			if( mSource != null )
			{
				if( mSource instanceof ComplexSource )
				{
					try
					{
						mProcessorTask = 
								mResourceManager.getThreadPoolManager()
									.schedule( ThreadType.DECODER, 
											   new ComplexProcessor(), 
											   50, TimeUnit.MILLISECONDS );

						((ComplexSource)mSource)
							.setListener( this.getComplexReceiver() );
					}
					catch( RejectedExecutionException ree )
					{
						mLog.error( getLogPrefix() + "ProcessingChain - "
								+ "error scheduling complex sample processing "
								+ "thread - " + ree.getLocalizedMessage() );
						
						mSource.dispose();
					}
				}
				else if( mSource instanceof SimplexSource )
				{
					try
					{
						mProcessorTask = 
								mResourceManager.getThreadPoolManager()
									.schedule( ThreadType.DECODER, 
											   new SimplexProcessor(), 
											   50, TimeUnit.MILLISECONDS );

						((SimplexSource)mSource)
								.setListener( this.getSimplexReceiver() );
					}
					catch( RejectedExecutionException ree )
					{
						mLog.error( getLogPrefix() + "ProcessingChain - "
								+ "error scheduling float sample processing "
								+ "thread", ree );
						
						mSource.dispose();
					}
				}
			}
		}
	}
	
	public void updateDecoder()
	{
		if( mChannel.getEnabled() )
		{
			AliasList aliasList = null;
			
			if( mResourceManager != null )
			{
				aliasList = mResourceManager.getPlaylistManager()
						.getPlayist().getAliasDirectory().getAliasList(  
								mChannel.getAliasListName() );
			}

			/* Setup the channel state */
			mChannelState = DecoderFactory.getChannelStateNew( this, aliasList );

			if( mSource == null )
			{
				mChannelState.setState( State.NO_TUNER );
			}
			else
			{
				mDecoder = DecoderFactory.
						getDecoder( this, mSource.getSampleType(), aliasList );
				
				if( mDecoder != null )
				{
					/* Register to receive decoded messages and auxiliary messages */
					mDecoder.addMessageListener( ProcessingChain.this );

					/* Setup the audio output processing thread */
					String threadName = "SDRTrunk Audio - " + 
										mChannel.getSite().getName() +
										"-" + mChannel.getName();
					
					mAudioOutput = new AudioOutput( threadName );
					addSimplexListener( mAudioOutput );
					
					/* Automatic frequency control */
					if( mAFC != null )
					{
						mAFC.dispose();
						mAFC = null;
					}
					
					if( mSource instanceof TunerChannelSource &&
					    mChannel.getDecodeConfiguration().supportsAFC() &&
						mChannel.getDecodeConfiguration().isAFCEnabled() )
					{
						TunerChannelSource tcs = (TunerChannelSource)mSource;
						
						mAFC = new AutomaticFrequencyControl( tcs );

						/* Register AFC to receive frequency change events */
						tcs.addListener( mAFC );

						/* Add AFC as listener to the decoder */
						mDecoder.addUnfilteredSimplexSampleListener( mAFC );
					}
				}
			}

			if( mAudioOutput != null )
			{
				/* Register audio output to be controlled by the channel state */
				mChannelState.addListener( (SquelchListener)mAudioOutput );
				mChannelState.setListener( (AudioTypeListener)mAudioOutput );
			}
		}
	}
	
	/**
	 * Starts, stops or changes event logging
	 */
	public void updateEventLogging()
	{
		/* Shutdown and remove existing event loggers */
		if( mEventLoggers != null )
		{
			for( EventLogger logger: mEventLoggers )
			{
				logger.stop();
			}
			
			mEventLoggers.clear();
		}
		
		/* Construct event loggers and add as message listeners */
		if( mChannel.getEnabled() && 
			mResourceManager != null )
		{
			//Get the event loggers
			ArrayList<EventLogType> types = getChannel()
					.getEventLogConfiguration().getLoggers();

			for( EventLogType type: types )
			{
				EventLogger logger;
				
				if( type == EventLogType.CALL_EVENT )
				{
					logger = mResourceManager.getEventLogManager()
							.getLogger( this, type );
					
					getChannelState().getCallEventModel()
						.addListener( (Listener<CallEvent>)logger );
				}
				else
				{
					logger = mResourceManager.getEventLogManager()
							.getLogger( this, type );

					addListener( (Listener<Message>)logger );
				}
				
				logger.start();

				mEventLoggers.add( logger );
			}
		}
	}

	/**
	 * Starts, stops or changes data recording
	 */
	public void updateRecording()
	{
		/* Remove any existing recorders */
		if( mRecorders != null )
		{
			for( Recorder recorder: mRecorders )
			{
				if( recorder != null )
				{
					try
	                {
		                recorder.stop();
	                }
					catch ( IOException ioe )
					{
						mLog.error( getLogPrefix() + "Couldn't stop recorder [" + 
		                		recorder.getFileName() + "]", ioe );
					}
				}
			}
		}

		/* Start any new recorders */
		if( mChannel.getEnabled() && 
			mResourceManager != null && 
			mDecoder != null )
		{
			mRecorders = mResourceManager.getRecorderManager()
					.getRecorder( this );

			/* Iterate any returned recorders and start/add them */
			for( Recorder recorder: mRecorders )
			{
				try
	            {
					recorder.start();

					if( recorder instanceof FloatWaveRecorder )
					{
						mLog.info( getLogPrefix() + "- started audio recording [" + 
								recorder.getFileName() + "]" );

						mDecoder.addSimplexSampleListener( (FloatWaveRecorder)recorder );
					}
					else if( recorder instanceof ComplexWaveRecorder )
					{
						mLog.info( getLogPrefix() + "- started baseband recording [" + 
								recorder.getFileName() + "]" );

						mDecoder.addComplexListener( (ComplexWaveRecorder )recorder );
					}
					
	            }
	            catch ( IOException e )
	            {
	            	mLog.error( getLogPrefix() + "Unable to start recorder [" + 
	            							recorder.getFileName() + "]" );
	            }
			}
		}
	}
	
	public Channel getChannel()
	{
		return mChannel;
	}
	
	public AudioOutput getAudioOutput()
	{
		return mAudioOutput;
	}
	
	public Source getSource()
	{
		return mSource;
	}
	
	/**
	 * Returns the tuner that is sourcing this channel, or null if it's being
	 * sourced from some other source
	 */
	public Tuner getTuner()
	{
		Tuner retVal = null;
		
		if( mSource instanceof TunerChannelSource )
		{
			retVal = ((TunerChannelSource)mSource).getTuner();
		}
		
		return retVal;
	}
	
	public Decoder getDecoder()
	{
		return mDecoder;
	}

	public ChannelState getChannelState()
	{
		return mChannelState;
	}
	
	public boolean isRunning()
	{
		return mRunning.get();
	}
	
	public boolean isProcessing()
	{
		return isRunning() && mSource != null;
	}

	private source.mixer.MixerChannel getMixerChannel()
	{
		source.mixer.MixerChannel retVal = null;
		
		if( mChannel.getSourceConfiguration() instanceof SourceConfigMixer )
		{
			SourceConfigMixer mixer = (SourceConfigMixer)mChannel.getSourceConfiguration();
			
			retVal = mixer.getChannel();
		}

		return retVal;
	}

	@Override
    public void receive( Message message )
    {
		if( message.isValid() )
		{
			mBroadcaster.receive( message );
		}
		
    }

	/**
	 * Adds the listener to receive demodulated samples from the decoder
	 */
    public void addSimplexListener( SimplexSampleListener listener )
    {
		if( mDecoder != null )
		{
			mDecoder.addSimplexSampleListener( listener );
		}
    }

    /**
     * Removes the listener from receiving demodulated samples from the decoder
     */
    public void removeSimplexListener( SimplexSampleListener listener )
    {
		if( mDecoder != null )
		{
			mDecoder.removeSimplexListener( listener );
		}
    }

    /**
     * Adds a listener to receive a copy of complex (I/Q) baseband samples 
     * received by this processing chain
     */
    public void addComplexListener( Listener<ComplexSample> listener )
    {
		if( mDecoder != null )
		{
			mDecoder.addComplexListener( listener );
		}
		else
		{
			mLog.error( getLogPrefix() + "attempt to add a complex sample "
					+ "listener, but the decoder is null" );
		}
    }

    /**
     * Removes the listener from receiving a copy of complex (I/Q) baseband 
     * samples received by this processing chain
     */
    public void removeComplexListener( Listener<ComplexSample> listener )
    {
		if( mDecoder != null )
		{
			mDecoder.removeComplexListener( listener );
		}
		else
		{
			mLog.error( getLogPrefix() + "attempt to remove a complex sample "
					+ "listener, but the decoder is null" );
		}
    }

    /**
     * Registers the listener to receive messages
     */
    public void addListener( Listener<Message> listener )
    {
    	mBroadcaster.addListener( listener );
    }
    
    /**
     * Registers all listeners in the list to receive messages
     */
    public void addListeners( List<Listener<Message>> listeners )
    {
    	for( Listener<Message> listener: listeners )
    	{
    		addListener( listener );
    	}
    }

    /**
     * Removes the listener from receiving messages
     */
    public void removeListener( Listener<Message> listener )
    {
		mBroadcaster.removeListener( listener );
    }

    /**
     * Manages the simplex sample queue and distributes samples to the decoder.
     */
	private class SimplexProcessor implements Runnable
	{
		@Override
        public void run()
        {
			List<SimplexBuffer> sampleBuffers = new ArrayList<SimplexBuffer>();

			mSimplexQueue.drainTo( sampleBuffers, 4 );

			for( SimplexBuffer sampleBuffer: sampleBuffers )
			{
				if( mDecoder != null )
				{
					float[] samples = sampleBuffer.getSamples();
					
					for( float sample: samples )
					{
						mDecoder.getSimplexReceiver().receive( sample );
					}
				}
				
				sampleBuffer.dispose();
			}
			
			sampleBuffers.clear();
        }
	}

	/**
     * Manages the complex sample queue and distributes samples to the decoder.
     */
	private class ComplexProcessor implements Runnable
	{
		@Override
        public void run()
        {
			List<List<ComplexSample>> sampleSets = 
								new ArrayList<List<ComplexSample>>();

			mComplexQueue.drainTo( sampleSets, 16 );

			for( List<ComplexSample> samples: sampleSets )
			{
				for( ComplexSample sample: samples )
				{
					if( sample != null && mDecoder != null )
					{
						mDecoder.getComplexReceiver().receive( sample );
					}
				}
				
				samples.clear();
			}
			
			sampleSets.clear();
        }
	}
	
	/**
	 * Internal listener to receive complex (I/Q) baseband samples.  Places 
	 * received samples into the queue managed by the ComplexProcessor.
	 */
	public class ComplexSampleReceiver implements Listener<List<ComplexSample>>
	{
		@Override
        public void receive( List<ComplexSample> samples )
        {
			mComplexQueue.add( samples );
        }
	}

	/**
	 * Internal listener to receive simplex (ie demodulated) samples.  Places
	 * received samples into the queue managed by the SimplexProcessor
	 */
	public class SimplexSampleReceiver implements Listener<SimplexBuffer>
	{
		@Override
        public void receive( SimplexBuffer samples )
        {
			mSimplexQueue.add( samples );
        }
	}
}