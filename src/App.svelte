<script>
  import { onMount } from 'svelte';
  import { io } from 'socket.io-client';

  // --- CONFIGURATION ---
  // Replace this with your deployed Render server URL
  const SIGNALING_SERVER_URL = "https://nexavoice-backend.onrender.com"; 
  
  // Put your TURN server credentials here
  const ICE_SERVERS = {
    iceServers: [
      { urls: "stun:stun.l.google.com:19302" },
      // Uncomment and add your TURN server details below:
      // {
      //   urls: "turn:your-turn-server.com:3478",
      //   username: "your_username",
      //   credential: "your_password"
      // }
    ]
  };

  // --- STATE ---
  let socket;
  let roomCode = "";
  let joined = false;
  let localStream;
  let isMuted = false;
  let isPTTPressed = false;
  
  let peers = {}; // Stores RTCPeerConnections
  let users = []; // Stores user state for UI { id, stream, speaking }

  // Svelte Action to easily attach media streams to <audio> HTML tags
  function srcObject(node, stream) {
    node.srcObject = stream;
    return {
      update(newStream) {
        node.srcObject = newStream;
      }
    };
  }

  // --- AUDIO ACTIVITY DETECTION ---
  // Runs a lightweight check every 150ms to see who is talking
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
        
        // Update the speaking status in the UI
        users = users.map(u => u.id === userId ? { ...u, speaking: avg > 15 } : u);
      }, 150);
    } catch (err) {
      console.log("Audio activity monitoring failed for user:", userId, err);
    }
  }

  // --- WEBRTC LOGIC ---
  async function joinRoom() {
    if (!roomCode.trim()) return alert("Enter a room code");

    try {
      // Request Microphone with strict gaming constraints
      localStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          sampleRate: 48000 // Opus default
        },
        video: false
      });

      // Add ourselves to the UI
      users = [...users, { id: "me", stream: localStream, speaking: false }];
      monitorAudioActivity(localStream, "me");

      // Connect to Signaling Server
      socket = io(SIGNALING_SERVER_URL);

      socket.on('connect', () => {
        socket.emit('join-room', roomCode);
        joined = true;
      });

      // When we join, server sends list of people already in room
      socket.on('existing-users', (existingUsers) => {
        existingUsers.forEach(userId => {
          createPeerConnection(userId, true);
        });
      });

      // When a new person joins
      socket.on('user-joined', (userId) => {
        createPeerConnection(userId, false);
      });

      // Handle WebRTC Signaling
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
      alert("Microphone access denied or error occurred.");
      console.error(err);
    }
  }

  function createPeerConnection(userId, isInitiator) {
    const pc = new RTCPeerConnection(ICE_SERVERS);
    peers[userId] = pc;

    // Send ICE candidates to the other user
    pc.onicecandidate = (event) => {
      if (event.candidate) {
        socket.emit('ice-candidate', { target: userId, candidate: event.candidate });
      }
    };

    // When we receive their audio stream
    pc.ontrack = (event) => {
      const remoteStream = event.streams[0];
      // Check if user already exists in UI to prevent duplicates
      if (!users.find(u => u.id === userId)) {
        users = [...users, { id: userId, stream: remoteStream, speaking: false }];
        monitorAudioActivity(remoteStream, userId);
      }
    };

    // Add our microphone to the connection
    localStream.getTracks().forEach(track => pc.addTrack(track, localStream));

    // If we are the initiator, create the offer
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

  // Push To Talk Logic
  function handlePTTDown() {
    if (isMuted) return; // Don't allow PTT if entirely muted
    isPTTPressed = true;
    localStream.getAudioTracks()[0].enabled = true;
  }

  function handlePTTUp() {
    if (isMuted) return;
    isPTTPressed = false;
    localStream.getAudioTracks()[0].enabled = false;
  }

  // Initially disable mic for Push-to-Talk mode
  $: if (localStream && !isMuted && !isPTTPressed) {
     localStream.getAudioTracks()[0].enabled = false;
  }
</script>

<main class="container">
  {#if !joined}
    <div class="login-box">
      <h1>NexaVoice</h1>
      <p>Low latency. Low CPU. Pure Voice.</p>
      <input type="text" placeholder="Enter Room Code" bind:value={roomCode} />
      <button class="btn-join" on:click={joinRoom}>Join Room</button>
    </div>
  {:else}
    <div class="room-box">
      <div class="header">
        <h2>Room: {roomCode}</h2>
        <span class="user-count">{users.length} Connected</span>
      </div>

      <div class="users-list">
        {#each users as user}
          <div class="user-card {user.speaking ? 'speaking' : ''}">
            <div class="avatar">
               {user.id === 'me' ? 'Me' : 'P'}
            </div>
            <span>{user.id === 'me' ? 'You' : 'Player'}</span>
            {#if user.id !== 'me'}
              <!-- Hidden audio tag for remote streams -->
              <audio autoplay use:srcObject={user.stream}></audio>
            {/if}
          </div>
        {/each}
      </div>

      <div class="controls">
        <button class="btn-mute {isMuted ? 'muted' : ''}" on:click={toggleMute}>
          {isMuted ? 'Unmute Mic' : 'Mute Mic'}
        </button>

        <button 
          class="btn-ptt {isPTTPressed ? 'active' : ''}" 
          on:pointerdown={handlePTTDown} 
          on:pointerup={handlePTTUp}
          on:pointerleave={handlePTTUp}
        >
          {isPTTPressed ? 'TALKING...' : 'HOLD TO TALK'}
        </button>
      </div>
    </div>
  {/if}
</main>

<style>
  /* Minimalist CSS for fast rendering and low CPU overhead */
  .container {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 100vh;
    padding: 20px;
    box-sizing: border-box;
  }

  .login-box, .room-box {
    width: 100%;
    max-width: 400px;
    background: #252542;
    padding: 30px;
    border-radius: 15px;
    box-shadow: 0 10px 30px rgba(0,0,0,0.5);
    text-align: center;
    display: flex;
    flex-direction: column;
    gap: 20px;
  }

  h1, h2 { margin: 0; color: #fff; }
  p { margin: 0; color: #a0a0b5; font-size: 14px; }

  input {
    padding: 15px;
    border-radius: 8px;
    border: none;
    font-size: 18px;
    text-align: center;
    background: #1a1a2e;
    color: white;
    outline: none;
  }

  button {
    padding: 15px;
    border: none;
    border-radius: 8px;
    font-size: 16px;
    font-weight: bold;
    cursor: pointer;
    transition: 0.2s;
    user-select: none;
  }

  .btn-join { background: #4caf50; color: white; }
  .btn-join:active { background: #388e3c; }

  .header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-bottom: 1px solid #3d3d5c;
    padding-bottom: 10px;
  }

  .user-count { font-size: 12px; background: #3d3d5c; padding: 5px 10px; border-radius: 20px;}

  .users-list {
    display: flex;
    flex-direction: column;
    gap: 10px;
    max-height: 50vh;
    overflow-y: auto;
  }

  .user-card {
    display: flex;
    align-items: center;
    gap: 15px;
    background: #1a1a2e;
    padding: 10px 15px;
    border-radius: 8px;
    border: 2px solid transparent;
    transition: 0.2s;
  }

  /* Voice Activity Indicator */
  .user-card.speaking { border-color: #4caf50; }

  .avatar {
    width: 40px;
    height: 40px;
    background: #3d3d5c;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-weight: bold;
  }

  .user-card.speaking .avatar { background: #4caf50; }

  .controls {
    display: flex;
    flex-direction: column;
    gap: 10px;
    margin-top: 10px;
  }

  .btn-mute { background: #e0e0e0; color: #333; }
  .btn-mute.muted { background: #f44336; color: white; }

  .btn-ptt {
    background: #2196f3;
    color: white;
    height: 80px;
    font-size: 20px;
    border-radius: 15px;
  }
  .btn-ptt.active {
    background: #ffeb3b;
    color: black;
    transform: scale(0.98);
  }
</style>