<script>
  import { onMount } from 'svelte';
  import { io } from 'socket.io-client';

  // --- CONFIGURATION ---
  const SIGNALING_SERVER_URL = "https://nexavoice-backend.onrender.com"; // Keep your Render URL here
  
  const ICE_SERVERS = {
    iceServers: [
      { urls: "stun:stun.l.google.com:19302" }
    ]
  };

  // --- STATE ---
  let socket;
  let roomCode = "";
  let joined = false;
  let localStream;
  let isMuted = false;
  
  let peers = {}; 
  // users array now tracks volume: { id, stream, speaking, volume }
  let users = []; 

  // Custom action to attach media streams AND control specific volume
  function audioSetup(node, { stream, volume }) {
    node.srcObject = stream;
    node.volume = volume;
    return {
      update(newParams) {
        if (node.srcObject !== newParams.stream) node.srcObject = newParams.stream;
        node.volume = newParams.volume;
      }
    };
  }

  // --- AUDIO ACTIVITY DETECTION ---
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
        analyser.getByteFrequencyData(dataArray);
        let sum = 0;
        for (let i = 0; i < bufferLength; i++) sum += dataArray[i];
        let avg = sum / bufferLength;
        
        users = users.map(u => u.id === userId ? { ...u, speaking: avg > 15 } : u);
      }, 150);
    } catch (err) {
      console.log("Audio activity monitoring failed", err);
    }
  }

  // --- WEBRTC LOGIC (OPTIMIZED FOR LOW LATENCY & QUALITY) ---
  async function joinRoom() {
    if (!roomCode.trim()) return alert("Enter a room code");

    try {
      // HIGH QUALITY, LOW LATENCY GAMING AUDIO REQUIRMENTS
      localStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          sampleRate: 48000,
          channelCount: 1, // Mono processes faster than stereo = less delay
          latency: 0
        },
        video: false
      });

      // ALWAYS ON VOICE: Mic is active immediately
      isMuted = false;
      localStream.getAudioTracks()[0].enabled = true;

      users = [...users, { id: "me", stream: localStream, speaking: false, volume: 1 }];
      monitorAudioActivity(localStream, "me");

      socket = io(SIGNALING_SERVER_URL);

      socket.on('connect', () => {
        socket.emit('join-room', roomCode);
        joined = true;
      });

      socket.on('existing-users', (existingUsers) => {
        existingUsers.forEach(userId => createPeerConnection(userId, true));
      });

      socket.on('user-joined', (userId) => {
        createPeerConnection(userId, false);
      });

      socket.on('offer', async (payload) => {
        const pc = createPeerConnection(payload.caller, false);
        await pc.setRemoteDescription(new RTCSessionDescription(payload.sdp));
        const answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);
        socket.emit('answer', { target: payload.caller, sdp: pc.localDescription });
      });

      socket.on('answer', async (payload) => {
        const pc = peers[payload.caller];
        if (pc) await pc.setRemoteDescription(new RTCSessionDescription(payload.sdp));
      });

      socket.on('ice-candidate', async (payload) => {
        const pc = peers[payload.caller];
        if (pc) await pc.addIceCandidate(new RTCIceCandidate(payload.candidate));
      });

      socket.on('user-disconnected', (userId) => {
        if (peers[userId]) {
          peers[userId].close();
          delete peers[userId];
        }
        users = users.filter(u => u.id !== userId);
      });

    } catch (err) {
      alert("Microphone access denied. Please allow mic permissions.");
      console.error(err);
    }
  }

  function createPeerConnection(userId, isInitiator) {
    const pc = new RTCPeerConnection(ICE_SERVERS);
    peers[userId] = pc;

    pc.onicecandidate = (event) => {
      if (event.candidate) {
        socket.emit('ice-candidate', { target: userId, candidate: event.candidate });
      }
    };

    pc.ontrack = (event) => {
      const remoteStream = event.streams[0];
      if (!users.find(u => u.id === userId)) {
        // Default new users to 100% volume
        users = [...users, { id: userId, stream: remoteStream, speaking: false, volume: 1 }];
        monitorAudioActivity(remoteStream, userId);
      }
    };

    localStream.getTracks().forEach(track => pc.addTrack(track, localStream));

    if (isInitiator) {
      pc.createOffer().then(offer => {
        pc.setLocalDescription(offer);
        socket.emit('offer', { target: userId, sdp: offer });
      });
    }

    return pc;
  }

  // --- CONTROLS ---
  function toggleMute() {
    isMuted = !isMuted;
    localStream.getAudioTracks()[0].enabled = !isMuted;
  }

  function updateUserVolume(id, event) {
    const newVolume = parseFloat(event.target.value);
    users = users.map(u => u.id === id ? { ...u, volume: newVolume } : u);
  }
</script>

<main class="app-container">
  {#if !joined}
    <div class="login-wrapper">
      <div class="logo-box">
        <div class="pulse-ring"></div>
        <h1>NEXA<span class="highlight">VOICE</span></h1>
      </div>
      <p class="subtitle">Zero Delay Gaming Comm</p>
      
      <div class="input-group">
        <input type="text" placeholder="ENTER SQUAD CODE" bind:value={roomCode} />
        <button class="btn-primary" on:click={joinRoom}>CONNECT</button>
      </div>
    </div>
  {:else}
    <div class="room-wrapper">
      <header class="room-header">
        <div>
          <h2 class="squad-title">SQUAD: <span class="highlight">{roomCode}</span></h2>
          <p class="ping-status">● Connected ({users.length}/10)</p>
        </div>
      </header>

      <div class="user-grid">
        {#each users as user (user.id)}
          <div class="user-card {user.speaking ? 'is-speaking' : ''}">
            <div class="user-info">
              <div class="avatar {user.speaking ? 'glow' : ''}">
                 {user.id === 'me' ? 'ME' : 'P'}
              </div>
              <span class="username">{user.id === 'me' ? 'You' : 'Teammate'}</span>
            </div>

            {#if user.id !== 'me'}
              <!-- SPECIFIC USER VOLUME SLIDER -->
              <div class="volume-control">
                <span class="vol-icon">🔊</span>
                <input 
                  type="range" 
                  min="0" max="1" step="0.05" 
                  value={user.volume} 
                  on:input={(e) => updateUserVolume(user.id, e)} 
                />
              </div>
              <!-- Hidden Audio tag attached to volume action -->
              <audio autoplay use:audioSetup={{ stream: user.stream, volume: user.volume }}></audio>
            {/if}
          </div>
        {/each}
      </div>

      <div class="bottom-bar">
        <button class="btn-mute {isMuted ? 'muted' : ''}" on:click={toggleMute}>
          {isMuted ? '🎤 MIC MUTED' : '🎙️ MIC ACTIVE'}
        </button>
      </div>
    </div>
  {/if}
</main>

<style>
  /* --- GAMER UI STYLES --- */
  :global(body) {
    margin: 0;
    padding: 0;
    background-color: #0b0b14;
    color: #ffffff;
    font-family: 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
    overflow: hidden; /* Prevent scrolling on mobile */
  }

  .app-container {
    height: 100vh;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    background: radial-gradient(circle at top, #1a1a3a 0%, #0b0b14 80%);
  }

  /* LOGIN SCREEN */
  .login-wrapper {
    width: 90%;
    max-width: 350px;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 20px;
  }

  .logo-box {
    position: relative;
    margin-bottom: 10px;
  }

  h1 {
    font-size: 38px;
    font-weight: 900;
    margin: 0;
    letter-spacing: 2px;
  }

  .highlight { color: #00ff88; }
  .subtitle { color: #8892b0; font-size: 14px; margin-top: -10px; font-weight: 600; letter-spacing: 1px; }

  .input-group {
    width: 100%;
    display: flex;
    flex-direction: column;
    gap: 15px;
  }

  input {
    width: 100%;
    padding: 18px;
    border-radius: 12px;
    border: 2px solid #23233a;
    background: #111122;
    color: white;
    font-size: 16px;
    font-weight: bold;
    text-align: center;
    box-sizing: border-box;
    outline: none;
    transition: 0.3s;
  }

  input:focus { border-color: #00ff88; box-shadow: 0 0 15px rgba(0, 255, 136, 0.2); }

  .btn-primary {
    width: 100%;
    padding: 18px;
    border: none;
    border-radius: 12px;
    background: #00ff88;
    color: #0b0b14;
    font-size: 18px;
    font-weight: 900;
    cursor: pointer;
    box-shadow: 0 5px 20px rgba(0, 255, 136, 0.3);
    transition: 0.2s;
  }
  .btn-primary:active { transform: scale(0.96); }

  /* ROOM SCREEN */
  .room-wrapper {
    width: 100%;
    height: 100%;
    display: flex;
    flex-direction: column;
  }

  .room-header {
    padding: 20px;
    background: rgba(17, 17, 34, 0.8);
    border-bottom: 1px solid #23233a;
    backdrop-filter: blur(10px);
  }

  .squad-title { margin: 0; font-size: 20px; font-weight: 800; }
  .ping-status { margin: 5px 0 0 0; font-size: 12px; color: #00ff88; font-weight: bold; }

  .user-grid {
    flex: 1;
    padding: 20px;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 15px;
  }

  .user-card {
    background: #15152a;
    border: 2px solid #23233a;
    border-radius: 16px;
    padding: 15px;
    display: flex;
    flex-direction: column;
    gap: 15px;
    transition: border-color 0.1s;
  }

  .user-card.is-speaking {
    border-color: #00ff88;
    box-shadow: 0 0 15px rgba(0, 255, 136, 0.1);
  }

  .user-info {
    display: flex;
    align-items: center;
    gap: 15px;
  }

  .avatar {
    width: 45px;
    height: 45px;
    border-radius: 12px;
    background: #23233a;
    display: flex;
    align-items: center;
    justify-content: center;
    font-weight: 900;
    color: #fff;
  }

  .avatar.glow { background: #00ff88; color: #0b0b14; }
  .username { font-size: 16px; font-weight: bold; }

  /* VOLUME SLIDER STYLES */
  .volume-control {
    display: flex;
    align-items: center;
    gap: 10px;
    background: #0e0e1a;
    padding: 10px 15px;
    border-radius: 10px;
  }

  .vol-icon { font-size: 16px; }

  input[type=range] {
    -webkit-appearance: none;
    width: 100%;
    background: transparent;
    padding: 0;
  }
  
  input[type=range]::-webkit-slider-runnable-track {
    width: 100%;
    height: 6px;
    background: #23233a;
    border-radius: 3px;
  }

  input[type=range]::-webkit-slider-thumb {
    -webkit-appearance: none;
    height: 18px;
    width: 18px;
    border-radius: 50%;
    background: #00ff88;
    margin-top: -6px;
  }

  /* BOTTOM BAR (ALWAYS ON MIC CONTROL) */
  .bottom-bar {
    padding: 20px;
    background: rgba(17, 17, 34, 0.9);
    border-top: 1px solid #23233a;
  }

  .btn-mute {
    width: 100%;
    padding: 20px;
    border-radius: 15px;
    border: none;
    font-size: 18px;
    font-weight: 900;
    background: #00ff88;
    color: #0b0b14;
    cursor: pointer;
    box-shadow: 0 5px 20px rgba(0, 255, 136, 0.2);
    transition: 0.2s;
  }

  .btn-mute.muted {
    background: #ff3366;
    color: white;
    box-shadow: 0 5px 20px rgba(255, 51, 102, 0.2);
  }
</style>