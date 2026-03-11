<script>
  import { onMount } from 'svelte';
  import { io } from 'socket.io-client';

  // --- CONFIGURATION ---
  const SIGNALING_SERVER_URL = "https://nexavoice-backend.onrender.com"; // Keep your Render URL here
  const ICE_SERVERS = { iceServers: [{ urls: "stun:stun.l.google.com:19302" }] };

  // --- SILENT AUDIO FOR BACKGROUND NOTIFICATION ---
  // This is a 1-second completely silent MP3 file in Base64. 
  // Looping this forces Android to show the Media Notification panel.
  const SILENT_MP3 = "data:audio/mpeg;base64,SUQzBAAAAAAAI1RTU0UAAAAPAAADTGF2ZjUwLjEyLjEwMAAAAAAAAAAAAAAA//MUxAAAAANIAAAAAExBTUUzLjEwMKqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq//MUxEQAAANIAAAAAExBTUUzLjEwMKqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq";

  // --- STATE ---
  let socket;
  let roomCode = "";
  let userName = "";
  
  let joined = false;
  let isConnecting = false;
  
  let isMicReady = false; // Tracks if they granted permission on load
  let localStream;
  let isMuted = true; // Start muted until they join a room
  
  let peers = {}; 
  let users = []; 

  // --- REQUEST MIC ON APP OPEN ---
  onMount(async () => {
    try {
      // Ask for permission the moment the app opens
      localStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true, noiseSuppression: true, autoGainControl: true,
          sampleRate: 48000, channelCount: 1, latency: 0
        },
        video: false
      });
      
      // Mute it initially so it's not transmitting before they join
      localStream.getAudioTracks()[0].enabled = false;
      isMicReady = true;
      
    } catch (err) {
      alert("Microphone permission is required! Please restart the app and allow access.");
    }
  });

  function audioSetup(node, { stream, volume }) {
    if (stream) node.srcObject = stream;
    node.volume = volume;
    return {
      update(newParams) {
        if (newParams.stream && node.srcObject !== newParams.stream) node.srcObject = newParams.stream;
        node.volume = newParams.volume;
      }
    };
  }

  // --- MEDIA NOTIFICATION FOR ANDROID ---
  function setupAndroidNotification() {
    // 1. Force play the silent audio element to trigger the OS Media Player
    const bgAudio = document.getElementById('bg-audio');
    if (bgAudio) {
      bgAudio.play().catch(e => console.log("Audio play blocked until interaction:", e));
    }

    // 2. Setup the text and buttons on the Notification Panel
    if ('mediaSession' in navigator) {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: `🔴 Live in Room: ${roomCode}`,
        artist: `NexaVoice - ${userName}`,
        album: 'Gaming Voice Chat'
      });

      // Map "Play" button to Unmute
      navigator.mediaSession.setActionHandler('play', () => {
        if (!localStream) return;
        isMuted = false;
        localStream.getAudioTracks()[0].enabled = true;
        navigator.mediaSession.playbackState = "playing";
      });
      
      // Map "Pause" button to Mute
      navigator.mediaSession.setActionHandler('pause', () => {
        if (!localStream) return;
        isMuted = true;
        localStream.getAudioTracks()[0].enabled = false;
        navigator.mediaSession.playbackState = "paused";
      });

      // Map "Stop" button to Disconnect
      navigator.mediaSession.setActionHandler('stop', () => {
        disconnectRoom();
      });

      navigator.mediaSession.playbackState = "playing";
    }
  }

  function monitorAudioActivity(stream, userId) {
    try {
      const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      const analyser = audioCtx.createAnalyser();
      const source = audioCtx.createMediaStreamSource(stream);
      source.connect(analyser);
      analyser.fftSize = 256;
      const bufferLength = analyser.frequencyBinCount;
      const dataArray = new Uint8Array(bufferLength);

      setInterval(() => {
        if (!joined) return;
        analyser.getByteFrequencyData(dataArray);
        let sum = 0;
        for (let i = 0; i < bufferLength; i++) sum += dataArray[i];
        let avg = sum / bufferLength;
        users = users.map(u => u.id === userId ? { ...u, speaking: avg > 15 } : u);
      }, 150);
    } catch (err) {}
  }

  async function joinRoom() {
    if (!isMicReady) return alert("Microphone is still loading or permission was denied.");
    if (!userName.trim()) return alert("Please enter your Name");
    if (!roomCode.trim()) return alert("Please enter a Room Code");
    if (isConnecting) return; 

    isConnecting = true;

    try {
      // Mic is already captured from onMount, just turn it on!
      isMuted = false;
      localStream.getAudioTracks()[0].enabled = true;

      users = [{ id: "me", name: userName, stream: localStream, speaking: false, volume: 1, canHearMe: true }];
      monitorAudioActivity(localStream, "me");

      socket = io(SIGNALING_SERVER_URL);

      socket.on('connect', () => {
        socket.emit('join-room', roomCode);
        joined = true;
        isConnecting = false;
        setupAndroidNotification();
      });

      socket.on('existing-users', (existingUsers) => {
        existingUsers.forEach(userId => createPeerConnection(userId, true));
      });

      socket.on('user-joined', (userId) => {
        createPeerConnection(userId, false);
      });

      socket.on('offer', async (payload) => {
        const pcData = createPeerConnection(payload.caller, false);
        await pcData.pc.setRemoteDescription(new RTCSessionDescription(payload.sdp));
        const answer = await pcData.pc.createAnswer();
        await pcData.pc.setLocalDescription(answer);
        socket.emit('answer', { target: payload.caller, sdp: pcData.pc.localDescription });
      });

      socket.on('answer', async (payload) => {
        const pcData = peers[payload.caller];
        if (pcData) await pcData.pc.setRemoteDescription(new RTCSessionDescription(payload.sdp));
      });

      socket.on('ice-candidate', async (payload) => {
        const pcData = peers[payload.caller];
        if (pcData) await pcData.pc.addIceCandidate(new RTCIceCandidate(payload.candidate));
      });

      socket.on('user-disconnected', (userId) => {
        if (peers[userId]) {
          peers[userId].pc.close();
          delete peers[userId];
        }
        users = users.filter(u => u.id !== userId);
      });

    } catch (err) {
      isConnecting = false;
      alert("Error connecting to room.");
    }
  }

  function createPeerConnection(userId, isInitiator) {
    const pc = new RTCPeerConnection(ICE_SERVERS);
    let dc;

    if (!users.find(u => u.id === userId)) {
      users = [...users, { id: userId, name: "Connecting...", stream: null, speaking: false, volume: 1, canHearMe: true }];
    }

    if (isInitiator) {
      dc = pc.createDataChannel('user-info');
      setupDataChannel(dc, userId);
    } else {
      pc.ondatachannel = (event) => setupDataChannel(event.channel, userId);
    }

    peers[userId] = { pc, dc };

    pc.onicecandidate = (event) => {
      if (event.candidate) socket.emit('ice-candidate', { target: userId, candidate: event.candidate });
    };

    pc.ontrack = (event) => {
      const remoteStream = event.streams[0];
      users = users.map(u => u.id === userId ? { ...u, stream: remoteStream } : u);
      monitorAudioActivity(remoteStream, userId);
    };

    localStream.getTracks().forEach(track => pc.addTrack(track, localStream));

    if (isInitiator) {
      pc.createOffer().then(offer => {
        pc.setLocalDescription(offer);
        socket.emit('offer', { target: userId, sdp: offer });
      });
    }

    return peers[userId];
  }

  function setupDataChannel(dc, userId) {
    dc.onopen = () => dc.send(JSON.stringify({ type: 'name', name: userName }));
    dc.onmessage = (event) => {
      const data = JSON.parse(event.data);
      if (data.type === 'name') {
        users = users.map(u => u.id === userId ? { ...u, name: data.name } : u);
      }
    };
  }

  function toggleMute() {
    isMuted = !isMuted;
    localStream.getAudioTracks()[0].enabled = !isMuted;
    if ('mediaSession' in navigator) {
      navigator.mediaSession.playbackState = isMuted ? "paused" : "playing";
    }
  }

  function updateUserVolume(id, event) {
    const newVolume = parseFloat(event.target.value);
    users = users.map(u => u.id === id ? { ...u, volume: newVolume } : u);
  }

  function toggleSendVoice(userId) {
    const user = users.find(u => u.id === userId);
    if (!user) return;
    
    const newCanHearMe = !user.canHearMe;
    users = users.map(u => u.id === userId ? { ...u, canHearMe: newCanHearMe } : u);

    const pcData = peers[userId];
    if (pcData && pcData.pc) {
      const sender = pcData.pc.getSenders()[0];
      if (sender) {
        sender.replaceTrack(newCanHearMe ? localStream.getAudioTracks()[0] : null);
      }
    }
  }

  function disconnectRoom() {
    // 1. Mute our mic (but don't destroy it so we can join again)
    isMuted = true;
    if (localStream) localStream.getAudioTracks()[0].enabled = false;
    
    // 2. Stop Peer Connections & Socket
    Object.values(peers).forEach(p => p.pc.close());
    if (socket) socket.disconnect();
    
    // 3. Stop background silent audio
    const bgAudio = document.getElementById('bg-audio');
    if (bgAudio) bgAudio.pause();

    // 4. Clear Notification Panel
    if ('mediaSession' in navigator) {
      navigator.mediaSession.playbackState = "none";
    }

    peers = {};
    users = [];
    joined = false;
    isConnecting = false;
  }
</script>

<main class="app-container">
  
  <!-- THE MAGIC BACKGROUND FIX: Loops silent audio to force Android Notification -->
  <audio id="bg-audio" loop src={SILENT_MP3}></audio>

  {#if !joined}
    <div class="login-wrapper">
      <div class="logo-box">
        <h1>NEXA<span class="highlight">VOICE</span></h1>
      </div>
      <p class="subtitle">Zero Delay Gaming Comm</p>
      
      <div class="input-group">
        <input type="text" placeholder="YOUR NAME" bind:value={userName} disabled={isConnecting || !isMicReady} />
        <input type="text" placeholder="SQUAD CODE" bind:value={roomCode} disabled={isConnecting || !isMicReady} />
        
        <button class="btn-primary" on:click={joinRoom} disabled={isConnecting || !isMicReady}>
          {!isMicReady ? 'ALLOW MIC PERMISSION...' : (isConnecting ? 'CONNECTING...' : 'CONNECT TO SQUAD')}
        </button>
      </div>
    </div>
  {:else}
    <div class="room-wrapper">
      <header class="room-header">
        <div>
          <h2 class="squad-title">SQUAD: <span class="highlight">{roomCode}</span></h2>
          <p class="ping-status">● {userName} Connected ({users.length}/10)</p>
        </div>
        <button class="btn-disconnect-small" on:click={disconnectRoom}>LEAVE</button>
      </header>

      <div class="user-grid">
        {#each users as user (user.id)}
          <div class="user-card {user.speaking ? 'is-speaking' : ''}">
            <div class="user-info">
              <div class="avatar {user.speaking ? 'glow' : ''}">
                 {user.name.charAt(0).toUpperCase()}
              </div>
              <span class="username">{user.name} {user.id === 'me' ? '(You)' : ''}</span>
            </div>

            {#if user.id !== 'me'}
              <div class="control-row">
                <span class="label">Their Mic:</span>
                <input type="range" min="0" max="1" step="0.05" value={user.volume} on:input={(e) => updateUserVolume(user.id, e)} />
              </div>
              
              <div class="control-row">
                <span class="label">Can hear me:</span>
                <button class="btn-toggle {user.canHearMe ? 'active' : ''}" on:click={() => toggleSendVoice(user.id)}>
                  {user.canHearMe ? '✅ YES' : '❌ NO'}
                </button>
              </div>

              {#if user.stream}
                <audio autoplay use:audioSetup={{ stream: user.stream, volume: user.volume }}></audio>
              {/if}
            {/if}
          </div>
        {/each}
      </div>

      <div class="bottom-bar">
        <button class="btn-mute {isMuted ? 'muted' : ''}" on:click={toggleMute}>
          {isMuted ? '🎤 MIC MUTED' : '🎙️ MIC ACTIVE'}
        </button>
        <button class="btn-disconnect" on:click={disconnectRoom}>DISCONNECT</button>
      </div>
    </div>
  {/if}
</main>

<style>
  :global(body) {
    margin: 0; padding: 0; background-color: #0b0b14; color: #ffffff;
    font-family: 'Segoe UI', Roboto, sans-serif; overflow: hidden;
  }
  .app-container { height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; background: radial-gradient(circle at top, #1a1a3a 0%, #0b0b14 80%); }

  .login-wrapper { width: 90%; max-width: 350px; display: flex; flex-direction: column; align-items: center; gap: 20px; }
  h1 { font-size: 38px; font-weight: 900; margin: 0; letter-spacing: 2px; }
  .highlight { color: #00ff88; }
  .subtitle { color: #8892b0; font-size: 14px; margin-top: -10px; font-weight: 600; }

  .input-group { width: 100%; display: flex; flex-direction: column; gap: 15px; }
  input { width: 100%; padding: 18px; border-radius: 12px; border: 2px solid #23233a; background: #111122; color: white; font-size: 16px; font-weight: bold; text-align: center; box-sizing: border-box; outline: none; transition: 0.3s; }
  input:focus { border-color: #00ff88; }
  input:disabled { opacity: 0.5; }

  .btn-primary { width: 100%; padding: 18px; border: none; border-radius: 12px; background: #00ff88; color: #0b0b14; font-size: 18px; font-weight: 900; cursor: pointer; transition: 0.2s; }
  .btn-primary:active { transform: scale(0.96); }
  .btn-primary:disabled { background: #555; color: #aaa; cursor: not-allowed; }

  .room-wrapper { width: 100%; height: 100%; display: flex; flex-direction: column; }
  .room-header { padding: 20px; background: rgba(17, 17, 34, 0.8); border-bottom: 1px solid #23233a; display: flex; justify-content: space-between; align-items: center; }
  .squad-title { margin: 0; font-size: 20px; font-weight: 800; }
  .ping-status { margin: 5px 0 0 0; font-size: 12px; color: #00ff88; font-weight: bold; }
  
  .btn-disconnect-small { background: #ff3366; color: white; border: none; padding: 8px 15px; border-radius: 8px; font-weight: bold; cursor: pointer;}

  .user-grid { flex: 1; padding: 20px; overflow-y: auto; display: flex; flex-direction: column; gap: 15px; }
  .user-card { background: #15152a; border: 2px solid #23233a; border-radius: 16px; padding: 15px; display: flex; flex-direction: column; gap: 15px; }
  .user-card.is-speaking { border-color: #00ff88; }
  .user-info { display: flex; align-items: center; gap: 15px; }
  .avatar { width: 45px; height: 45px; border-radius: 12px; background: #23233a; display: flex; align-items: center; justify-content: center; font-weight: 900; font-size: 20px;}
  .avatar.glow { background: #00ff88; color: #0b0b14; }
  .username { font-size: 16px; font-weight: bold; }

  .control-row { display: flex; align-items: center; justify-content: space-between; background: #0e0e1a; padding: 10px; border-radius: 10px; font-size: 14px; font-weight: bold; color: #8892b0;}
  .label { width: 40%; }
  input[type=range] { width: 55%; margin: 0; }
  
  .btn-toggle { width: 55%; padding: 5px; border: none; border-radius: 5px; background: #333; color: white; font-weight: bold; cursor: pointer; }
  .btn-toggle.active { background: #00ff88; color: #0b0b14; }

  .bottom-bar { padding: 20px; background: rgba(17, 17, 34, 0.9); border-top: 1px solid #23233a; display: flex; gap: 10px; }
  .btn-mute { flex: 2; padding: 15px; border-radius: 12px; border: none; font-size: 16px; font-weight: 900; background: #00ff88; color: #0b0b14; cursor: pointer; }
  .btn-mute.muted { background: #555; color: white; }
  .btn-disconnect { flex: 1; padding: 15px; border-radius: 12px; border: none; font-size: 16px; font-weight: 900; background: #ff3366; color: white; cursor: pointer; }
</style>