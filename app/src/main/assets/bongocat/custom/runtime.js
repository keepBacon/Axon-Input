(() => {
  'use strict';

  const CONFIG = window.__AXON_STYLE_CONFIG__ || {};
  const DESIGN_WIDTH = Math.max(1, Number(CONFIG.designWidth) || 612);
  const DESIGN_HEIGHT = Math.max(1, Number(CONFIG.designHeight) || 354);
  const FRAME_RATE_LIMIT = Math.max(1, Math.min(240, Number(CONFIG.mverFrameRateLimit) || 60));
  const FRAME_INTERVAL = 1000 / FRAME_RATE_LIMIT;
  const DAMPING_DECAY = 0.75;
  const MIN_MOUSE_PRESS_MS = 36;
  // Direct evaluateJavascript events are authoritative. Native polling is only a desync safety net,
  // so running it every animation frame wastes bridge/JSON work on high-refresh-rate devices.
  const NATIVE_KEY_RECONCILE_INTERVAL_MS = 120;
  const RENDER_DPR_CAP_NORMAL = Math.max(1, Math.min(3, Number(CONFIG.renderDprCapNormal) || Number(CONFIG.renderDprCap) || 2));
  const RENDER_DPR_CAP_CLEAR = Math.max(RENDER_DPR_CAP_NORMAL, Math.min(3, Number(CONFIG.renderDprCapClear) || 3));
  let renderQuality = String(CONFIG.renderQuality || 'normal') === 'clear' ? 'clear' : 'normal';
  let renderDprCap = renderQuality === 'clear' ? RENDER_DPR_CAP_CLEAR : RENDER_DPR_CAP_NORMAL;
  const TEXTURE_UPLOAD_YIELD_EVERY = Math.max(1, Math.min(8, Number(CONFIG.textureUploadYieldEvery) || 2));
  const TEXTURE_ESTIMATED_RGBA_BYTES = Math.max(0, Number(CONFIG.textureEstimatedRgbaBytes) || 0);
  const PHYSICS_ACTIVE_FPS = Math.max(15, Math.min(120, Number(CONFIG.physicsActiveFps) || 60));
  const PHYSICS_IDLE_FPS = Math.max(12, Math.min(PHYSICS_ACTIVE_FPS, Number(CONFIG.physicsIdleFps) || 30));
  let physicsControls = normalizePhysicsControls(CONFIG.physicsControls);

  const LEFT_KEYS = new Set(Array.isArray(CONFIG.leftKeys) ? CONFIG.leftKeys : []);
  const RIGHT_KEYS = new Set(Array.isArray(CONFIG.rightKeys) ? CONFIG.rightKeys : []);
  const LEFT_KEY_ASSETS = CONFIG.leftKeyAssets && typeof CONFIG.leftKeyAssets === 'object' ? CONFIG.leftKeyAssets : {};
  const RIGHT_KEY_ASSETS = CONFIG.rightKeyAssets && typeof CONFIG.rightKeyAssets === 'object' ? CONFIG.rightKeyAssets : {};
  const STYLE_MODE = String(CONFIG.mode || 'keyboard');
  const IS_MVER = CONFIG.mver === true || String(CONFIG.format || '') === 'mver016';
  const MVER_USE_LIVE2D = IS_MVER && CONFIG.useLive2d === true;
  // Mver is a layered compositor, not a single image. Keep every exported layer in the
  // package design coordinate system so imported animations retain their original geometry.
  const MVER_RENDER_HAND_OVERLAYS = IS_MVER && CONFIG.mverRenderHandOverlays === true;
  // Full-frame Mver hand PNGs contain the skin author's exact keyboard-hand placement. When they
  // are authoritative they replace the model's built-in keyboard hand while active; the runtime
  // uses CatParam*HandDown only as a temporary hide gate so the PNG and model arm never coexist.
  const MVER_SPRITE_HANDS_AUTHORITATIVE = IS_MVER && CONFIG.mverSpriteHandsAuthoritative === true;
  const MVER_RENDER_MOUSE_OVERLAY = IS_MVER && CONFIG.mverRenderMouseOverlay === true;
  const MVER_BASE_BACKGROUND = IS_MVER ? String(CONFIG.mverMouseBg || '') : '';
  const MVER_KEY_SPRITES = CONFIG.mverKeySprites && typeof CONFIG.mverKeySprites === 'object' ? CONFIG.mverKeySprites : {};
  const MVER_LEFT_HAND_SPRITES = CONFIG.mverLeftHandSprites && typeof CONFIG.mverLeftHandSprites === 'object' ? CONFIG.mverLeftHandSprites : {};
  const MVER_RIGHT_HAND_SPRITES = CONFIG.mverRightHandSprites && typeof CONFIG.mverRightHandSprites === 'object' ? CONFIG.mverRightHandSprites : {};
  const MVER_HAND_SPRITES = CONFIG.mverHandSprites && typeof CONFIG.mverHandSprites === 'object' ? CONFIG.mverHandSprites : {};
  const MVER_KEY_BINDINGS = Array.isArray(CONFIG.mverKeyBindings) ? CONFIG.mverKeyBindings : [];
  const MVER_LEFT_HAND_BINDINGS = Array.isArray(CONFIG.mverLeftHandBindings) ? CONFIG.mverLeftHandBindings : [];
  const MVER_RIGHT_HAND_BINDINGS = Array.isArray(CONFIG.mverRightHandBindings) ? CONFIG.mverRightHandBindings : [];
  const MVER_HAND_BINDINGS = Array.isArray(CONFIG.mverHandBindings) ? CONFIG.mverHandBindings : [];
  const MVER_FACE_BINDINGS = Array.isArray(CONFIG.mverFaceBindings) ? CONFIG.mverFaceBindings : [];
  const MVER_FACE_ASSETS = Array.isArray(CONFIG.mverFaceAssets) ? CONFIG.mverFaceAssets : [];
  const MVER_EXPRESSION_BINDINGS = Array.isArray(CONFIG.mverExpressionBindings) ? CONFIG.mverExpressionBindings : [];
  const MVER_MOTION_BINDINGS = Array.isArray(CONFIG.mverMotionBindings) ? CONFIG.mverMotionBindings : [];
  const MVER_MOTION_LOCK_HAND_BINDINGS = Array.isArray(CONFIG.mverMotionLockHandBindings) ? CONFIG.mverMotionLockHandBindings : [];
  const MVER_SOUND_BINDINGS = Array.isArray(CONFIG.mverSoundBindings) ? CONFIG.mverSoundBindings : [];
  const MVER_EXPRESSIONS = Array.isArray(CONFIG.expressions) ? CONFIG.expressions : [];
  const MVER_MOTIONS = Array.isArray(CONFIG.motions) ? CONFIG.motions : [];
  const MVER_MOTION_GROUPS = CONFIG.motionGroups && typeof CONFIG.motionGroups === 'object' ? CONFIG.motionGroups : {};
  const MVER_PHYSICS = CONFIG.physics && typeof CONFIG.physics === 'object' ? CONFIG.physics : null;
  const PHYSICS_GROUP_KEYS = buildPhysicsGroupKeys(MVER_PHYSICS);
  const LIVE2D_POSE = CONFIG.pose && typeof CONFIG.pose === 'object' ? CONFIG.pose : null;
  const LIVE2D_EYE_BLINK_IDS = Array.isArray(CONFIG.eyeBlinkIds) ? CONFIG.eyeBlinkIds.map(String).filter(Boolean) : [];
  const LIVE2D_LIP_SYNC_IDS = Array.isArray(CONFIG.lipSyncIds) ? CONFIG.lipSyncIds.map(String).filter(Boolean) : [];
  const PARAMETER_DEFINITIONS = Array.isArray(CONFIG.parameterDefinitions) ? CONFIG.parameterDefinitions : [];
  const KEY_PARAMETER_MAP = CONFIG.keyParameterMap && typeof CONFIG.keyParameterMap === 'object' ? CONFIG.keyParameterMap : {};
  const KEYBOARD_DOWN_PARAMETER_IDS = Array.isArray(CONFIG.keyboardDownParameterIds) ? CONFIG.keyboardDownParameterIds.map(String).filter(Boolean) : [];
  const MOUSE_X_PARAMETER_IDS = Array.isArray(CONFIG.mouseXParameterIds) ? CONFIG.mouseXParameterIds.map(String).filter(Boolean) : [];
  const MOUSE_Y_PARAMETER_IDS = Array.isArray(CONFIG.mouseYParameterIds) ? CONFIG.mouseYParameterIds.map(String).filter(Boolean) : [];
  const MOUSE_LEFT_PARAMETER_IDS = Array.isArray(CONFIG.mouseLeftParameterIds) ? CONFIG.mouseLeftParameterIds.map(String).filter(Boolean) : [];
  const MOUSE_RIGHT_PARAMETER_IDS = Array.isArray(CONFIG.mouseRightParameterIds) ? CONFIG.mouseRightParameterIds.map(String).filter(Boolean) : [];
  const SAVED_PARAMETER_VALUES = CONFIG.savedParameterValues && typeof CONFIG.savedParameterValues === 'object' ? CONFIG.savedParameterValues : {};
  const PARAMETER_LOCKS = CONFIG.parameterLocks && typeof CONFIG.parameterLocks === 'object' ? CONFIG.parameterLocks : {};
  const MVER_EMOTICON_CLEAR = Array.isArray(CONFIG.mverEmoticonClear) ? CONFIG.mverEmoticonClear : [];
  const MVER_EMOTICON_KEEP = CONFIG.mverEmoticonKeep === true;
  const MVER_SOUND_CLEAR = Array.isArray(CONFIG.mverSoundClear) ? CONFIG.mverSoundClear : [];
  const MVER_SOUND_KEEP = CONFIG.mverSoundKeep === true;
  const MVER_MOUSE_LEFT_KEYS = Array.isArray(CONFIG.mverMouseLeftKeys) ? CONFIG.mverMouseLeftKeys : [];
  const MVER_MOUSE_RIGHT_KEYS = Array.isArray(CONFIG.mverMouseRightKeys) ? CONFIG.mverMouseRightKeys : [];
  const MVER_MOUSE_SIDE_KEYS = Array.isArray(CONFIG.mverMouseSideKeys) ? CONFIG.mverMouseSideKeys : [];
  const MVER_MOUSE_SPEED = Math.max(0.01, Number(CONFIG.mverMouseSpeed) || 1);
  const MVER_MOUSE_FORCE_MOVE = CONFIG.mverMouseForceMove === true;
  const MVER_WORKAREA_ENABLED = CONFIG.mverWorkareaEnabled === true;
  const MVER_WORKAREA_TOP_LEFT = Array.isArray(CONFIG.mverWorkareaTopLeft) ? CONFIG.mverWorkareaTopLeft : [];
  const MVER_WORKAREA_RIGHT_BOTTOM = Array.isArray(CONFIG.mverWorkareaRightBottom) ? CONFIG.mverWorkareaRightBottom : [];
  const MVER_L2D_CORRECT = Math.max(0.01, Number(CONFIG.mverL2dCorrect) || 1);
  const AXON_LIVE2D_DISPLAY = CONFIG.axonLive2dDisplay === true;
  let AXON_LIVE2D_DISPLAY_SCALE = Math.max(0.5, Math.min(2.0, Number(CONFIG.axonLive2dDisplayScale) || 1));
  let AXON_LIVE2D_DISPLAY_OFFSET_X = Math.max(-0.9, Math.min(0.9, Number(CONFIG.axonLive2dDisplayOffsetX) || 0));
  let AXON_LIVE2D_DISPLAY_OFFSET_Y = Math.max(-0.9, Math.min(0.9, Number(CONFIG.axonLive2dDisplayOffsetY) || 0));
  let AXON_LIVE2D_HIDE_WATERMARK = CONFIG.axonLive2dHideWatermark === true;
  const AXON_LIVE2D_WATERMARK_PARAMETERS = Array.isArray(CONFIG.axonLive2dWatermarkParameters)
    ? CONFIG.axonLive2dWatermarkParameters.map((id) => String(id || '')).filter(Boolean)
    : [];
  const AXON_LIVE2D_WATERMARK_PARTS = Array.isArray(CONFIG.axonLive2dWatermarkParts)
    ? CONFIG.axonLive2dWatermarkParts.map((id) => String(id || '')).filter(Boolean)
    : [];
  const AXON_WATERMARK_PART_BASE = new Map();
  const MVER_MOUSE_SCALE = Math.max(0.01, Number(CONFIG.mverMouseScale) || 1);
  const MVER_MOUSE_OFFSET_X = Number(CONFIG.mverMouseOffsetX) || 0;
  const MVER_MOUSE_OFFSET_Y = Number(CONFIG.mverMouseOffsetY) || 0;
  const MVER_HAND_OFFSET_X = Number(CONFIG.mverHandOffsetX) || 0;
  const MVER_HAND_OFFSET_Y = Number(CONFIG.mverHandOffsetY) || 0;
  const MVER_LEFT_HANDED = CONFIG.mverLeftHanded === true;
  const MVER_L2D_OFFSET = Array.isArray(CONFIG.mverL2dOffset) ? CONFIG.mverL2dOffset : [];
  // Mver 0.1.6 exposes l2d_horizontal_flip in config, but its standard Live2D runtime
  // does not mirror the rendered model with it. Keep the source value only for diagnostics;
  // applying CSS scaleX(-1) here mirrors the character while leaving the authored desk/base
  // layers untouched, which breaks asymmetric/swapped layouts.
  const MVER_SOURCE_L2D_HORIZONTAL_FLIP = CONFIG.mverL2dHorizontalFlip === true;
  // Importer supplies per-package compositor semantics. Dedicated l2d*bg packages use their
  // authored coordinates; legacy plain mousebg/tabletbg fallbacks can receive a small calibrated offset.
  const MVER_FULLFRAME_OFFSET_X = IS_MVER ? (Number(CONFIG.mverFullFrameOffsetX) || 0) : 0;
  const MVER_FULLFRAME_OFFSET_Y = IS_MVER ? (Number(CONFIG.mverFullFrameOffsetY) || 0) : 0;
  const MVER_BASE_LAYER_Z = Math.max(0, Math.min(8, Number(CONFIG.mverBaseLayerZ) || 1));
  const MVER_BASE_LAYER_ROLE = String(CONFIG.mverBaseLayerRole || 'background');
  const MVER_ARM_LINE_COLOR = Array.isArray(CONFIG.mverArmLineColor) ? CONFIG.mverArmLineColor : [0, 0, 0];

  // Horizontal input mirror only. The cat model and background never flip.
  // Pairs follow the exact key rows drawn by the bundled BongoCat keyboard asset.
  const MIRROR_KEYS = new Map([
    ['BackQuote', 'Backspace'], ['Backspace', 'BackQuote'],
    ['Num1', 'Num0'], ['Num0', 'Num1'],
    ['Num2', 'Num9'], ['Num9', 'Num2'],
    ['Num3', 'Num8'], ['Num8', 'Num3'],
    ['Num4', 'Num7'], ['Num7', 'Num4'],
    ['Num5', 'Num6'], ['Num6', 'Num5'],
    ['Tab', 'Delete'], ['Delete', 'Tab'],
    ['KeyQ', 'KeyP'], ['KeyP', 'KeyQ'],
    ['KeyW', 'KeyO'], ['KeyO', 'KeyW'],
    ['KeyE', 'KeyI'], ['KeyI', 'KeyE'],
    ['KeyR', 'KeyU'], ['KeyU', 'KeyR'],
    ['KeyT', 'KeyY'], ['KeyY', 'KeyT'],
    ['CapsLock', 'Return'], ['Return', 'CapsLock'],
    ['KeyA', 'KeyL'], ['KeyL', 'KeyA'],
    ['KeyS', 'KeyK'], ['KeyK', 'KeyS'],
    ['KeyD', 'KeyJ'], ['KeyJ', 'KeyD'],
    ['KeyF', 'KeyH'], ['KeyH', 'KeyF'],
    ['KeyG', 'KeyG'],
    ['ShiftLeft', 'ShiftRight'], ['ShiftRight', 'ShiftLeft'],
    ['KeyZ', 'Slash'], ['Slash', 'KeyZ'],
    ['KeyX', 'KeyM'], ['KeyM', 'KeyX'],
    ['KeyC', 'KeyN'], ['KeyN', 'KeyC'],
    ['KeyV', 'KeyB'], ['KeyB', 'KeyV'],
    ['ControlLeft', 'ControlRight'], ['ControlRight', 'ControlLeft'],
    ['Alt', 'AltGr'], ['AltGr', 'Alt'],
    ['LeftArrow', 'RightArrow'], ['RightArrow', 'LeftArrow'],
    ['UpArrow', 'UpArrow'], ['DownArrow', 'DownArrow'],
    ['Space', 'Space'], ['Meta', 'Meta'], ['Escape', 'Escape'], ['Fn', 'Fn'],
    ['Control', 'Control'], ['Shift', 'Shift'],
  ]);

  const state = {
    leftKey: null,
    rightKey: null,
    mouseButtons: 0,
    mouseVisualButtons: 0,
    mousePressUntil: [0, 0],
    cursorX: 0.5,
    cursorY: 0.5,
    targetX: 0.5,
    targetY: 0.5,
    pointerActive: false,
    globalReverse: false,
    ready: false,
    runtimeError: '',
    inputCount: 0,
    lastInput: '',
    mverPressed: new Set(),
    mverPressOrder: [],
    mverLx: 0,
    mverLy: 0,
    mverRx: 0,
    mverRy: 0,
    mverL3: false,
    mverR3: false,
    mverLatchedFaceIndex: -1,
    mverExpressionIndex: -1,
    mverExpressionStartedAt: 0,
    debugExpressionKind: 'auto',
    debugExpressionIndex: -1,
    debugExpressionStartedAt: 0,
    debugExpressionWeight: 1,
    mverBindingStates: new Map(),
    mverBindingOrder: new Map(),
    mverBindingSequence: 0,
    mverComboStates: new Map(),
    mverMotionIndex: -1,
    mverMotionStartedAt: 0,
    mverMotionLockHand: false,
    modernPressed: new Set(),
    modernGamepadPressed: new Set(),
    modernParameterCounts: new Map(),
    parameterToggles: new Map(),
    parameterPulseUntil: new Map(),
    // Test-preview state is separate from authored hotkey state.
    parameterPreviewRestoreToggle: new Map(),
    parameterSequenceEvents: [],
    parameterSequenceGeneration: 0,
    functionExpressionUntil: 0,
    functionExpressionRestoreKind: 'auto',
    functionExpressionRestoreIndex: -1,
    functionExpressionRestoreStartedAt: 0,
    staticFallback: false,
  };

  const stage = document.getElementById('stage');
  const canvas = document.getElementById('live2dCanvas');
  const fallback = document.getElementById('fallback');
  const leftImage = document.getElementById('leftKey');
  const rightImage = document.getElementById('rightKey');

  function setAssetImage(image, src) {
    if (!src) { image.classList.add('hidden'); image.removeAttribute('src'); return; }
    image.src = String(src);
    image.classList.remove('hidden');
  }
  setAssetImage(document.getElementById('background'), CONFIG.background);
  setAssetImage(fallback, CONFIG.cover);

  function assetUrl(relativePath) {
    const base = String(CONFIG.baseUri || '');
    return base + String(relativePath || '').split('/').map(encodeURIComponent).join('/');
  }

  const mverLayers = new Map();

  function mverLayer(id, zIndex = 4) {
    if (!IS_MVER) return null;
    if (mverLayers.has(id)) return mverLayers.get(id);
    const image = document.createElement('img');
    image.className = 'layer hidden mver-layer';
    image.alt = '';
    image.dataset.mverId = id;
    image.style.zIndex = String(zIndex);
    stage.appendChild(image);
    mverLayers.set(id, image);
    return image;
  }

  function setMverFullLayer(id, src, visible, zIndex = 4) {
    const image = mverLayer(id, zIndex);
    if (!image) return;
    if (!visible || !src) {
      image.classList.add('hidden');
      return;
    }
    // Mver/SFML draws standard sprites at design-space origin using their authored pixel size.
    // Do not center/stretch cropped community frames; preserve 0,0 compositing semantics.
    image.dataset.designX = String(MVER_FULLFRAME_OFFSET_X);
    image.dataset.designY = String(MVER_FULLFRAME_OFFSET_Y);
    image.dataset.assetScale = '1';
    image.dataset.centered = '0';
    image.dataset.mverFullFrame = '1';
    const next = String(src);
    if (image.getAttribute('src') !== next) {
      image.onload = () => applyMverPlacedLayout(image);
      image.src = next;
    }
    image.classList.remove('hidden');
    applyMverPlacedLayout(image);
  }

  function mverDesignFit() {
    const width = Math.max(1, stage.clientWidth || innerWidth || DESIGN_WIDTH);
    const height = Math.max(1, stage.clientHeight || innerHeight || DESIGN_HEIGHT);
    const scale = Math.min(width / DESIGN_WIDTH, height / DESIGN_HEIGHT);
    return {
      scale,
      offsetX: (width - DESIGN_WIDTH * scale) * 0.5,
      offsetY: (height - DESIGN_HEIGHT * scale) * 0.5,
    };
  }

  function applyMverPlacedLayout(image) {
    if (!image || image.classList.contains('hidden')) return;
    const designX = Number(image.dataset.designX);
    const designY = Number(image.dataset.designY);
    const assetScale = Math.max(0.001, Number(image.dataset.assetScale) || 1);
    const centered = image.dataset.centered === '1';
    const fit = mverDesignFit();
    const naturalWidth = Math.max(1, image.naturalWidth || Number(image.dataset.naturalWidth) || 1);
    const naturalHeight = Math.max(1, image.naturalHeight || Number(image.dataset.naturalHeight) || 1);
    image.dataset.naturalWidth = String(naturalWidth);
    image.dataset.naturalHeight = String(naturalHeight);
    let left = fit.offsetX + designX * fit.scale;
    let top = fit.offsetY + designY * fit.scale;
    const width = naturalWidth * assetScale * fit.scale;
    const height = naturalHeight * assetScale * fit.scale;
    if (centered) {
      left -= width * 0.5;
      top -= height * 0.5;
    }
    image.style.inset = 'auto';
    image.style.objectFit = 'fill';
    image.style.left = `${left}px`;
    image.style.top = `${top}px`;
    image.style.width = `${width}px`;
    image.style.height = `${height}px`;
    image.style.transform = '';
  }

  function setMverPlacedLayer(id, src, visible, x, y, assetScale = 1, zIndex = 7, centered = false) {
    const image = mverLayer(id, zIndex);
    if (!image) return;
    if (!visible || !src) {
      image.classList.add('hidden');
      return;
    }
    image.dataset.designX = String(Number(x) || 0);
    image.dataset.designY = String(Number(y) || 0);
    image.dataset.assetScale = String(Math.max(0.001, Number(assetScale) || 1));
    image.dataset.centered = centered ? '1' : '0';
    const next = String(src);
    if (image.getAttribute('src') !== next) {
      image.onload = () => applyMverPlacedLayout(image);
      image.src = next;
    }
    image.classList.remove('hidden');
    applyMverPlacedLayout(image);
  }

  function setMverPointLayer(id, src, visible, xRatio, yRatio, zIndex = 7) {
    setMverPlacedLayer(
      id, src, visible,
      Math.max(0, Math.min(1, Number(xRatio) || 0)) * DESIGN_WIDTH,
      Math.max(0, Math.min(1, Number(yRatio) || 0)) * DESIGN_HEIGHT,
      1, zIndex, true,
    );
  }

  addEventListener('resize', () => {
    for (const image of mverLayers.values()) {
      if (image.dataset.designX !== undefined) applyMverPlacedLayout(image);
    }
    drawMverArm();
  }, { passive: true });

  function objectHas(object, key) {
    return object && Object.prototype.hasOwnProperty.call(object, key) && Boolean(object[key]);
  }

  function bindingKeys(binding) {
    return binding && Array.isArray(binding.keys) ? binding.keys.map(String) : [];
  }

  function bindingsContainKey(bindings, key) {
    return Array.isArray(bindings) && bindings.some((binding) => bindingKeys(binding).includes(key));
  }

  function hasMverSemantic(key) {
    return objectHas(MVER_KEY_SPRITES, key)
      || objectHas(MVER_LEFT_HAND_SPRITES, key)
      || objectHas(MVER_RIGHT_HAND_SPRITES, key)
      || objectHas(MVER_HAND_SPRITES, key)
      || bindingsContainKey(MVER_KEY_BINDINGS, key)
      || bindingsContainKey(MVER_LEFT_HAND_BINDINGS, key)
      || bindingsContainKey(MVER_RIGHT_HAND_BINDINGS, key)
      || bindingsContainKey(MVER_HAND_BINDINGS, key)
      || bindingsContainKey(MVER_FACE_BINDINGS, key)
      || bindingsContainKey(MVER_EXPRESSION_BINDINGS, key)
      || bindingsContainKey(MVER_MOTION_BINDINGS, key)
      || bindingsContainKey(MVER_MOTION_LOCK_HAND_BINDINGS, key)
      || bindingsContainKey(MVER_SOUND_BINDINGS, key)
      || MVER_EMOTICON_CLEAR.includes(key)
      || MVER_SOUND_CLEAR.includes(key)
      || MVER_MOUSE_LEFT_KEYS.includes(key)
      || MVER_MOUSE_RIGHT_KEYS.includes(key)
      || MVER_MOUSE_SIDE_KEYS.includes(key);
  }

  function normalizeMverSemantic(raw, allowMirror) {
    let key = String(raw || '');
    if (!key) return null;
    const candidates = [key];
    if (key.startsWith('Control')) candidates.push('Control');
    if (key.startsWith('Shift')) candidates.push('Shift');
    if (key === 'AltGr') candidates.push('Alt');
    if (key.startsWith('Meta')) candidates.push('Meta');
    if (/^F\d+$/.test(key)) candidates.push('Fn');

    for (const candidate of candidates) {
      let resolved = candidate;
      if (allowMirror && state.globalReverse) {
        const mirrored = MIRROR_KEYS.get(candidate);
        if (mirrored && hasMverSemantic(mirrored)) resolved = mirrored;
      }
      if (hasMverSemantic(resolved)) return resolved;
      if (hasMverSemantic(candidate)) return candidate;
    }
    return null;
  }

  function bindingPressed(binding) {
    const keys = bindingKeys(binding);
    return keys.length > 0 && keys.every((key) => state.mverPressed.has(key));
  }

  function bindingRank(binding) {
    let rank = -1;
    for (const key of bindingKeys(binding)) {
      rank = Math.max(rank, state.mverPressOrder.lastIndexOf(key));
    }
    return rank;
  }

  function bindingStateKey(group, index) {
    return `${group}:${index}`;
  }

  function updateBindingGroup(group, bindings, onActivate) {
    for (let i = 0; i < bindings.length; i++) {
      const binding = bindings[i];
      const id = bindingStateKey(group, i);
      const active = bindingPressed(binding);
      const wasActive = state.mverBindingStates.get(id) === true;
      if (active && !wasActive) {
        state.mverBindingSequence += 1;
        state.mverBindingOrder.set(id, state.mverBindingSequence);
        if (onActivate) onActivate(binding, i);
      }
      state.mverBindingStates.set(id, active);
    }
  }

  function latestActiveBinding(group, bindings) {
    let selected = null;
    let selectedRank = -1;
    for (let i = 0; i < bindings.length; i++) {
      const binding = bindings[i];
      if (!bindingPressed(binding)) continue;
      const rank = Number(state.mverBindingOrder.get(bindingStateKey(group, i))) || bindingRank(binding);
      if (rank >= selectedRank) {
        selected = binding;
        selectedRank = rank;
      }
    }
    return selected;
  }

  function latestPressedBinding(bindings) {
    let selected = null;
    let selectedRank = -1;
    for (const binding of bindings) {
      if (!bindingPressed(binding)) continue;
      const rank = bindingRank(binding);
      if (rank >= selectedRank) {
        selected = binding;
        selectedRank = rank;
      }
    }
    return selected;
  }

  function latestPressedFor(map) {
    for (let i = state.mverPressOrder.length - 1; i >= 0; i--) {
      const key = state.mverPressOrder[i];
      if (state.mverPressed.has(key) && objectHas(map, key)) return key;
    }
    return null;
  }

  function comboRising(id, keys) {
    const active = isComboPressed(keys);
    const previous = state.mverComboStates.get(id) === true;
    state.mverComboStates.set(id, active);
    return active && !previous;
  }

  const mverAudioByBinding = new Map();
  const mverOverlappingAudio = new Set();
  let mverMotionAudio = null;

  function safePlayAudio(audio) {
    if (!audio) return;
    try {
      const promise = audio.play();
      if (promise && typeof promise.catch === 'function') promise.catch(() => {});
    } catch (_) {}
  }

  function stopAudio(audio) {
    if (!audio) return;
    try { audio.pause(); } catch (_) {}
    try { audio.currentTime = 0; } catch (_) {}
  }

  function playMverSound(binding, slot) {
    const src = String(binding && binding.src || '');
    if (!src) return;
    if (MVER_SOUND_KEEP) {
      const audio = new Audio(src);
      audio.preload = 'auto';
      mverOverlappingAudio.add(audio);
      const cleanup = () => mverOverlappingAudio.delete(audio);
      audio.addEventListener('ended', cleanup, { once: true });
      audio.addEventListener('error', cleanup, { once: true });
      safePlayAudio(audio);
      return;
    }
    let audio = mverAudioByBinding.get(slot);
    if (!audio || audio.dataset.axonSrc !== src) {
      audio = new Audio(src);
      audio.preload = 'auto';
      audio.dataset.axonSrc = src;
      mverAudioByBinding.set(slot, audio);
    }
    stopAudio(audio);
    safePlayAudio(audio);
  }

  function stopAllMverSounds() {
    for (const audio of mverAudioByBinding.values()) stopAudio(audio);
    for (const audio of mverOverlappingAudio) stopAudio(audio);
    mverOverlappingAudio.clear();
    stopAudio(mverMotionAudio);
    mverMotionAudio = null;
  }

  function motionDurationSeconds(index) {
    const item = MVER_MOTIONS[index];
    const json = item && item.json && typeof item.json === 'object' ? item.json : null;
    const duration = json && json.Meta ? Number(json.Meta.Duration) : 0;
    return Number.isFinite(duration) && duration > 0 ? duration : 0;
  }

  function mverMotionGroup(groupName) {
    if (Array.isArray(MVER_MOTION_GROUPS[groupName])) return MVER_MOTION_GROUPS[groupName];
    const wanted = String(groupName || '').toLowerCase();
    for (const [name, value] of Object.entries(MVER_MOTION_GROUPS)) {
      if (String(name).toLowerCase() === wanted && Array.isArray(value)) return value;
    }
    return [];
  }

  function resolveMverMotionIndex(bindingIndex, lockHand) {
    const groupName = lockHand ? 'CAT_motion_lock' : 'CAT_motion';
    const group = mverMotionGroup(groupName);
    const slot = Math.max(0, Number(bindingIndex) | 0);
    const resolved = Number(group[slot]);
    return Number.isInteger(resolved) && resolved >= 0 && resolved < MVER_MOTIONS.length ? resolved : -1;
  }

  function startMverMotion(index, lockHand) {
    const resolved = Number(index) | 0;
    if (resolved < 0 || !MVER_MOTIONS[resolved]) return;
    state.mverMotionIndex = resolved;
    state.mverMotionStartedAt = performance.now();
    state.mverMotionLockHand = Boolean(lockHand);
    const sound = String(MVER_MOTIONS[resolved].sound || '');
    if (sound) {
      stopAudio(mverMotionAudio);
      mverMotionAudio = new Audio(sound);
      mverMotionAudio.preload = 'auto';
      safePlayAudio(mverMotionAudio);
    }
  }

  function updateMverMotionLifetime(now = performance.now()) {
    if (state.mverMotionIndex < 0) return;
    const current = MVER_MOTIONS[state.mverMotionIndex];
    const meta = current && current.json && typeof current.json === 'object' ? (current.json.Meta || current.json.meta || {}) : {};
    if (meta.Loop === true || meta.loop === true) return;
    const duration = motionDurationSeconds(state.mverMotionIndex);
    if (duration <= 0) return;
    if ((now - state.mverMotionStartedAt) / 1000 >= duration) {
      const wasLocked = state.mverMotionLockHand;
      state.mverMotionIndex = -1;
      state.mverMotionStartedAt = 0;
      state.mverMotionLockHand = false;
      if (wasLocked) {
        syncMverHands();
        syncHandOverrides();
      }
    }
  }

  function syncMverKeys() {
    if (MVER_KEY_BINDINGS.length) {
      for (let index = 0; index < MVER_KEY_BINDINGS.length; index++) {
        const binding = MVER_KEY_BINDINGS[index];
        const src = String(binding.src || '');
        setMverFullLayer(`mver-key-binding-${index}`, src, Boolean(src) && bindingPressed(binding), 5);
      }
      return;
    }
    for (const [key, src] of Object.entries(MVER_KEY_SPRITES)) {
      setMverFullLayer(`mver-key-${key}`, src, state.mverPressed.has(key) && Boolean(src), 5);
    }
  }

  function handBindingSource(group, bindings, legacyMap) {
    if (bindings.length) {
      const binding = latestActiveBinding(group, bindings);
      return binding ? String(binding.src || '') : '';
    }
    const key = latestPressedFor(legacyMap);
    return key ? String(legacyMap[key] || '') : '';
  }

  function syncMverHands() {
    if (!IS_MVER) return;
    if (!MVER_RENDER_HAND_OVERLAYS) {
      setMverFullLayer('mver-left-hand', '', false, 6);
      setMverFullLayer('mver-right-hand', '', false, 6);
      setMverFullLayer('mver-hand', '', false, 6);
      return;
    }
    // motion_lockhand keeps the normal input-hand layer from overriding a Live2D motion.
    if (state.mverMotionLockHand && state.mverMotionIndex >= 0) {
      const up = String(CONFIG.mverUp || '');
      setMverFullLayer('mver-left-hand', String(CONFIG.mverLeftIdle || ''), Boolean(CONFIG.mverLeftIdle), 6);
      setMverFullLayer('mver-right-hand', String(CONFIG.mverRightIdle || ''), Boolean(CONFIG.mverRightIdle), 6);
      setMverFullLayer('mver-hand', up, Boolean(up), 6);
      return;
    }

    const left = handBindingSource('left-hand', MVER_LEFT_HAND_BINDINGS, MVER_LEFT_HAND_SPRITES);
    const right = handBindingSource('right-hand', MVER_RIGHT_HAND_BINDINGS, MVER_RIGHT_HAND_SPRITES);
    const hand = handBindingSource('hand', MVER_HAND_BINDINGS, MVER_HAND_SPRITES);

    const leftSrc = left || String(CONFIG.mverLeftIdle || '');
    const rightSrc = right || String(CONFIG.mverRightIdle || '');
    const handSrc = hand || String(CONFIG.mverUp || '');
    setMverFullLayer('mver-left-hand', leftSrc, Boolean(leftSrc), 6);
    setMverFullLayer('mver-right-hand', rightSrc, Boolean(rightSrc), 6);
    setMverFullLayer('mver-hand', handSrc, Boolean(handSrc), 6);
  }

  function isComboPressed(keys) {
    return Array.isArray(keys) && keys.length > 0 && keys.every((key) => state.mverPressed.has(String(key)));
  }

  function isAnyMverKeyPressed(keys) {
    return Array.isArray(keys) && keys.some((key) => state.mverPressed.has(String(key)));
  }

  function syncMverFaceAndExpressions() {
    if (!IS_MVER) return;
    if (state.debugExpressionKind === 'face' && state.debugExpressionIndex >= 0) {
      const forced = MVER_FACE_ASSETS.find((item) => Number(item.index) === state.debugExpressionIndex)
        || MVER_FACE_BINDINGS.find((item) => Number(item.index) === state.debugExpressionIndex);
      const src = forced ? String(forced.src || '') : '';
      setMverFullLayer('mver-face', src, Boolean(src), 9);
      return;
    }
    const face = latestActiveBinding('face', MVER_FACE_BINDINGS);
    if (MVER_EMOTICON_KEEP) {
      const src = state.mverLatchedFaceIndex >= 0
        ? String((MVER_FACE_BINDINGS.find((item) => Number(item.index) === state.mverLatchedFaceIndex) || {}).src || '')
        : '';
      setMverFullLayer('mver-face', src, Boolean(src), 9);
    } else {
      const src = face ? String(face.src || '') : '';
      setMverFullLayer('mver-face', src, Boolean(src), 9);
    }
  }

  function syncMverBindingActions() {
    updateBindingGroup('left-hand', MVER_LEFT_HAND_BINDINGS);
    updateBindingGroup('right-hand', MVER_RIGHT_HAND_BINDINGS);
    updateBindingGroup('hand', MVER_HAND_BINDINGS);
    updateBindingGroup('face', MVER_FACE_BINDINGS, (binding) => {
      if (MVER_EMOTICON_KEEP) state.mverLatchedFaceIndex = Number(binding.index);
    });
    updateBindingGroup('expression', MVER_EXPRESSION_BINDINGS, (binding) => {
      state.mverExpressionIndex = Math.max(0, Number(binding.index) || 0);
      state.mverExpressionStartedAt = performance.now();
    });
    updateBindingGroup('motion', MVER_MOTION_BINDINGS, (binding) => {
      startMverMotion(resolveMverMotionIndex(binding.index, false), false);
    });
    updateBindingGroup('motion-lock', MVER_MOTION_LOCK_HAND_BINDINGS, (binding) => {
      startMverMotion(resolveMverMotionIndex(binding.index, true), true);
    });
    updateBindingGroup('sound', MVER_SOUND_BINDINGS, (binding, slot) => {
      playMverSound(binding, slot);
    });

    if (comboRising('emoticon-clear', MVER_EMOTICON_CLEAR)) {
      state.mverLatchedFaceIndex = -1;
      state.mverExpressionIndex = -1;
      state.mverExpressionStartedAt = 0;
    }
    if (comboRising('sound-clear', MVER_SOUND_CLEAR)) stopAllMverSounds();
  }

  function setMverKey(raw, pressed, gamepad) {
    if (!IS_MVER) return false;
    const key = normalizeMverSemantic(raw, !gamepad);
    if (!key) return false;

    if (pressed) {
      state.mverPressed.add(key);
      state.mverPressOrder = state.mverPressOrder.filter((item) => item !== key);
      state.mverPressOrder.push(key);
    } else {
      state.mverPressed.delete(key);
      state.mverPressOrder = state.mverPressOrder.filter((item) => item !== key);
    }
    syncMverBindingActions();
    syncMverKeys();
    syncMverHands();
    syncMverFaceAndExpressions();
    syncMverMouseVisual();
    syncHandOverrides();
    return true;
  }

  function binomial(n, k) {
    if (k < 0 || k > n) return 0;
    if (k === 0 || k === n) return 1;
    let result = 1;
    const count = Math.min(k, n - k);
    for (let i = 1; i <= count; i++) result = result * (n - count + i) / i;
    return result;
  }

  function bezierPoint(t, points) {
    const n = points.length - 1;
    let x = 0;
    let y = 0;
    const oneMinus = 1 - t;
    for (let i = 0; i <= n; i++) {
      const weight = binomial(n, i) * (oneMinus ** (n - i)) * (t ** i);
      x += points[i][0] * weight;
      y += points[i][1] * weight;
    }
    return [x, y];
  }

  function rightHandGeometry(fx, fy) {
    const x = -97 * fx + 44 * fy + 184;
    const y = -76 * fx - 40 * fy + 324;
    const oof = 6;
    const pss = [[211, 159]];
    let dist = Math.hypot(211 - x, 159 - y);
    const centreLeft = [211 - 0.7237 * dist / 2, 159 + 0.69 * dist / 2];
    for (let i = 1; i < oof; i++) pss.push(bezierPoint(i / oof, [[211, 159], centreLeft, [x, y]]));
    pss.push([x, y]);

    let a = y - centreLeft[1];
    let b = centreLeft[0] - x;
    let length = Math.max(0.0001, Math.hypot(a, b));
    a = x + a / length * 60;
    b = y + b / length * 60;
    const anchor = [258, 228];
    dist = Math.hypot(anchor[0] - a, anchor[1] - b);
    const centreRight = [anchor[0] - 0.6 * dist / 2, anchor[1] + 0.8 * dist / 2];
    const push = 20;
    let sx = x - centreLeft[0];
    let sy = y - centreLeft[1];
    length = Math.max(0.0001, Math.hypot(sx, sy));
    sx *= push / length;
    sy *= push / length;
    let sx2 = a - centreRight[0];
    let sy2 = b - centreRight[1];
    length = Math.max(0.0001, Math.hypot(sx2, sy2));
    sx2 *= push / length;
    sy2 *= push / length;
    for (let i = 1; i < oof; i++) {
      pss.push(bezierPoint(i / oof, [[x, y], [x + sx, y + sy], [a + sx2, b + sy2], [a, b]]));
    }
    pss.push([a, b]);
    for (let i = oof - 1; i > 0; i--) pss.push(bezierPoint(i / oof, [anchor, centreRight, [a, b]]));
    pss.push(anchor);

    const points = [];
    for (let i = 0; i <= 25; i++) {
      const point = bezierPoint(i / 25, pss);
      points.push([point[0] - 38 + MVER_HAND_OFFSET_X, point[1] - 50 + MVER_HAND_OFFSET_Y]);
    }
    return {
      points,
      // Upstream Mver applies decoration.offsetX/offsetY to the mouse/tablet device itself.
      // hand_offset belongs to hand/arm geometry; adding it here shifts the whole device and can
      // place the mouse directly over the keyboard on community 0.1.6 packs.
      deviceX: (a + x) / 2 - 67 - 38 + MVER_MOUSE_OFFSET_X,
      deviceY: (b + y) / 2 - 29 - 50 + MVER_MOUSE_OFFSET_Y,
    };
  }

  let mverArmCanvasElement = null;
  let mverArmImage = null;
  let mverLastArmGeometry = null;

  function ensureMverArm() {
    if (!IS_MVER || STYLE_MODE !== 'standard' || !CONFIG.mverArm) return null;
    if (!mverArmCanvasElement) {
      mverArmCanvasElement = document.createElement('canvas');
      mverArmCanvasElement.className = 'layer hidden';
      mverArmCanvasElement.style.zIndex = '4';
      mverArmCanvasElement.style.pointerEvents = 'none';
      stage.appendChild(mverArmCanvasElement);
    }
    if (!mverArmImage) {
      mverArmImage = new Image();
      mverArmImage.onload = drawMverArm;
      mverArmImage.src = String(CONFIG.mverArm);
    }
    return mverArmCanvasElement;
  }

  function drawMverArm() {
    const armCanvas = ensureMverArm();
    if (!armCanvas || !mverLastArmGeometry) return;
    const cssWidth = Math.max(1, stage.clientWidth || innerWidth || DESIGN_WIDTH);
    const cssHeight = Math.max(1, stage.clientHeight || innerHeight || DESIGN_HEIGHT);
    const dpr = Math.max(1, devicePixelRatio || 1);
    const pixelWidth = Math.max(1, Math.round(cssWidth * dpr));
    const pixelHeight = Math.max(1, Math.round(cssHeight * dpr));
    if (armCanvas.width !== pixelWidth || armCanvas.height !== pixelHeight) {
      armCanvas.width = pixelWidth;
      armCanvas.height = pixelHeight;
    }
    const context = armCanvas.getContext('2d');
    context.setTransform(dpr, 0, 0, dpr, 0, 0);
    context.clearRect(0, 0, cssWidth, cssHeight);
    const fit = mverDesignFit();
    const points = mverLastArmGeometry.points.map(([x, y]) => [fit.offsetX + x * fit.scale, fit.offsetY + y * fit.scale]);
    if (points.length < 3) return;
    context.save();
    context.beginPath();
    context.moveTo(points[0][0], points[0][1]);
    for (let i = 1; i < points.length; i++) context.lineTo(points[i][0], points[i][1]);
    context.closePath();
    context.clip();
    const xs = points.map((point) => point[0]);
    const ys = points.map((point) => point[1]);
    const minX = Math.min(...xs);
    const maxX = Math.max(...xs);
    const minY = Math.min(...ys);
    const maxY = Math.max(...ys);
    if (mverArmImage && mverArmImage.complete && mverArmImage.naturalWidth > 0) {
      context.drawImage(mverArmImage, minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
    }
    context.restore();
    const r = Math.max(0, Math.min(255, Number(MVER_ARM_LINE_COLOR[0]) || 0));
    const g = Math.max(0, Math.min(255, Number(MVER_ARM_LINE_COLOR[1]) || 0));
    const b = Math.max(0, Math.min(255, Number(MVER_ARM_LINE_COLOR[2]) || 0));
    context.beginPath();
    context.moveTo(points[0][0], points[0][1]);
    for (let i = 1; i < points.length; i++) context.lineTo(points[i][0], points[i][1]);
    context.closePath();
    context.lineWidth = Math.max(1, 6 * fit.scale);
    context.strokeStyle = `rgba(${r},${g},${b},0.78)`;
    context.stroke();
    armCanvas.classList.remove('hidden');
  }

  function syncMverMouseVisual() {
    if (!IS_MVER || STYLE_MODE !== 'standard') return;
    if (!MVER_RENDER_MOUSE_OVERLAY) {
      setMverPlacedLayer('mver-mouse-base', '', false, 0, 0);
      setMverPlacedLayer('mver-mouse-left', '', false, 0, 0);
      setMverPlacedLayer('mver-mouse-right', '', false, 0, 0);
      if (mverArmCanvasElement) mverArmCanvasElement.classList.add('hidden');
      return;
    }
    let fx = Math.max(0, Math.min(1, state.cursorX));
    const fy = Math.max(0, Math.min(1, state.cursorY));
    if (MVER_LEFT_HANDED) fx = 1 - fx;
    if (state.globalReverse) fx = 1 - fx;
    const geometry = rightHandGeometry(fx, fy);
    mverLastArmGeometry = geometry;
    const x = geometry.deviceX;
    const y = geometry.deviceY;
    const base = String(CONFIG.mverMouse || '');
    const left = String(CONFIG.mverMouseLeft || '');
    const right = String(CONFIG.mverMouseRight || '');
    const side = String(CONFIG.mverMouseSide || '');
    const leftDown = Boolean(state.mouseVisualButtons & 1) || isAnyMverKeyPressed(MVER_MOUSE_LEFT_KEYS);
    const rightDown = Boolean(state.mouseVisualButtons & 2) || isAnyMverKeyPressed(MVER_MOUSE_RIGHT_KEYS);
    const sideDown = Boolean(state.mouseVisualButtons & 4) || isAnyMverKeyPressed(MVER_MOUSE_SIDE_KEYS);
    setMverPlacedLayer('mver-mouse-base', base, Boolean(base), x, y, MVER_MOUSE_SCALE, 4, false);
    setMverPlacedLayer('mver-mouse-left', left, Boolean(left) && leftDown, x, y, MVER_MOUSE_SCALE, 4, false);
    setMverPlacedLayer('mver-mouse-right', right, Boolean(right) && rightDown, x, y, MVER_MOUSE_SCALE, 4, false);
    setMverPlacedLayer('mver-mouse-side', side, Boolean(side) && sideDown, x, y, MVER_MOUSE_SCALE, 4, false);
    drawMverArm();
  }

  const mverPreloadedAssets = new Map();

  function preloadMverAsset(src) {
    const url = String(src || '');
    if (!url || mverPreloadedAssets.has(url)) return;
    const image = new Image();
    image.decoding = 'async';
    image.src = url;
    mverPreloadedAssets.set(url, image);
  }

  function preloadMverLayers() {
    if (!IS_MVER) return;
    const bindings = [
      ...MVER_KEY_BINDINGS, ...MVER_LEFT_HAND_BINDINGS, ...MVER_RIGHT_HAND_BINDINGS,
      ...MVER_HAND_BINDINGS, ...MVER_FACE_BINDINGS,
    ];
    for (const binding of bindings) preloadMverAsset(binding && binding.src);
    for (const map of [MVER_KEY_SPRITES, MVER_LEFT_HAND_SPRITES,
      MVER_RIGHT_HAND_SPRITES, MVER_HAND_SPRITES]) {
      for (const src of Object.values(map || {})) preloadMverAsset(src);
    }
    for (const src of [MVER_BASE_BACKGROUND, CONFIG.mverUp, CONFIG.mverLeftIdle, CONFIG.mverRightIdle,
      CONFIG.mverMouse, CONFIG.mverMouseLeft, CONFIG.mverMouseRight, CONFIG.mverMouseSide,
      CONFIG.mverArm]) preloadMverAsset(src);
  }

  function initializeMverLayers() {
    if (!IS_MVER) return;
    preloadMverLayers();
    // Layer role comes from the importer: l2d*bg preserves Mver's authored foreground compositor,
    // while plain mousebg/tabletbg fallback stays behind Live2D. This avoids skin-specific z-index hacks.
    setMverFullLayer('mver-standard-base', MVER_BASE_BACKGROUND, Boolean(MVER_BASE_BACKGROUND), MVER_BASE_LAYER_Z);
    leftImage.classList.add('hidden');
    rightImage.classList.add('hidden');
    syncMverKeys();
    syncMverHands();
    syncMverFaceAndExpressions();
    syncMverMouseVisual();
  }

  let renderer = null;
  let lastFrameTime = 0;
  let animationHandle = 0;
  let recoveryScheduled = false;
  let runtimePaused = false;
  let runtimeDisposed = false;

  function scheduleAnimation() {
    if (runtimePaused || runtimeDisposed || animationHandle) return;
    animationHandle = requestAnimationFrame(tick);
  }

  function pauseRuntime() {
    if (runtimeDisposed || runtimePaused) return;
    runtimePaused = true;
    if (animationHandle) cancelAnimationFrame(animationHandle);
    animationHandle = 0;
    lastFrameTime = 0;
    stopAllMverSounds();
  }

  function resumeRuntime() {
    if (runtimeDisposed) return;
    runtimePaused = false;
    lastFrameTime = 0;
    nextNativeKeyReconcileAt = 0;
    scheduleAnimation();
  }

  function disposeRuntime() {
    if (runtimeDisposed) return;
    runtimeDisposed = true;
    runtimePaused = true;
    if (animationHandle) cancelAnimationFrame(animationHandle);
    animationHandle = 0;
    stopAllMverSounds();
    try { if (renderer && typeof renderer.dispose === 'function') renderer.dispose(); } catch (_) {}
    renderer = null;
    state.ready = false;
  }

  function scheduleRendererRecovery() {
    if (runtimeDisposed || recoveryScheduled) return;
    recoveryScheduled = true;
    setTimeout(() => location.reload(), 120);
  }

  canvas.addEventListener('webglcontextlost', (event) => {
    event.preventDefault();
    scheduleRendererRecovery();
  }, false);
  canvas.addEventListener('webglcontextrestored', scheduleRendererRecovery, false);

  // CubismEyeBlink defaults from the same Cubism framework used by easy-live2d.
  const blink = {
    phase: 'first',
    phaseStartedAt: 0,
    nextAt: 0,
  };

  function nextBlinkTime(now) {
    // CubismEyeBlink: userTime + random * (2 * 4.0 - 1.0)
    return now + Math.random() * 7000;
  }

  function eyeBlinkValue(now) {
    if (blink.phase === 'first') {
      blink.phase = 'interval';
      blink.nextAt = nextBlinkTime(now);
      return 1;
    }

    if (blink.phase === 'interval') {
      if (now >= blink.nextAt) {
        blink.phase = 'closing';
        blink.phaseStartedAt = now;
      }
      return 1;
    }

    if (blink.phase === 'closing') {
      let t = (now - blink.phaseStartedAt) / 100;
      if (t >= 1) {
        t = 1;
        blink.phase = 'closed';
        blink.phaseStartedAt = now;
      }
      return 1 - t;
    }

    if (blink.phase === 'closed') {
      const t = (now - blink.phaseStartedAt) / 50;
      if (t >= 1) {
        blink.phase = 'opening';
        blink.phaseStartedAt = now;
      }
      return 0;
    }

    let t = (now - blink.phaseStartedAt) / 150;
    if (t >= 1) {
      t = 1;
      blink.phase = 'interval';
      blink.nextAt = nextBlinkTime(now);
    }
    return t;
  }

  function keySide(key) {
    if (LEFT_KEYS.has(key)) return 'left';
    if (RIGHT_KEYS.has(key)) return 'right';
    return null;
  }

  function resolveSupportedKey(key) {
    const value = String(key || '');
    if (keySide(value)) return value;
    if (/^F\d+$/.test(value) && LEFT_KEYS.has('Fn')) return 'Fn';
    for (const prefix of ['Meta', 'Shift', 'Alt', 'Control']) {
      if (value.startsWith(prefix) && LEFT_KEYS.has(prefix)) return prefix;
    }
    return null;
  }

  function inputKey(key) {
    const supported = resolveSupportedKey(key);
    if (!supported) return null;
    if (!state.globalReverse) return supported;
    const mirrored = MIRROR_KEYS.get(supported) || supported;
    return keySide(mirrored) ? mirrored : supported;
  }

  function setOverlay(side, key) {
    const image = side === 'left' ? leftImage : rightImage;
    if (!key) {
      image.classList.add('hidden');
      image.removeAttribute('src');
      return;
    }
    const assets = side === 'left' ? LEFT_KEY_ASSETS : RIGHT_KEY_ASSETS;
    // New imports provide exact file URIs so WEBP/JPEG overlays and non-lowercase resource
    // directories work. Keep the legacy PNG path as a compatibility fallback for old imports.
    image.src = String(assets[key] || assetUrl(`resources/${side}-keys/${key}.png`));
    image.classList.remove('hidden');
  }

  function anyBindingPressed(bindings) {
    return Array.isArray(bindings) && bindings.some((binding) => bindingPressed(binding));
  }

  function syncHandOverrides() {
    if (!renderer) return;
    if (IS_MVER) {
      // Full-frame authored Mver hand layers encode the exact keyboard-hand pose. Treat them as
      // the visible authority and use CatParam*HandDown only to hide the model's idle hand while
      // a replacement sprite is active, preventing two arm systems from being visible together.
      if (MVER_SPRITE_HANDS_AUTHORITATIVE) {
        // Full-canvas hand sprites are drawn on top of the Live2D model. In many Mver
        // standard models CatParam*HandDown is not a second animation trigger: value 1
        // hides the model's built-in idle hand so the authored PNG can replace it. Leaving
        // the parameter at its default (usually 0) keeps the model hand visible underneath
        // the PNG and produces the familiar "double arm / two hands" artifact.
        //
        // Only hide a model hand while its authored replacement sprite is actually active.
        // Missing parameters are ignored by setFrameInput, so this remains safe for community
        // models that only use raster hand layers.
        const genericHandActive = anyBindingPressed(MVER_HAND_BINDINGS);
        const leftHandActive = genericHandActive || anyBindingPressed(MVER_LEFT_HAND_BINDINGS);
        const rightHandActive = anyBindingPressed(MVER_RIGHT_HAND_BINDINGS);
        const lockHand = state.mverMotionLockHand && state.mverMotionIndex >= 0;
        if (lockHand) {
          // lockhand motions own the model arm and suppress the normal input-hand sprite.
          renderer.clearFrameInput('CatParamLeftHandDown');
          renderer.clearFrameInput('CatParamRightHandDown');
          return;
        }
        if (leftHandActive) renderer.setFrameInput('CatParamLeftHandDown', 1);
        else renderer.clearFrameInput('CatParamLeftHandDown');
        if (rightHandActive) renderer.setFrameInput('CatParamRightHandDown', 1);
        else renderer.clearFrameInput('CatParamRightHandDown');
        return;
      }
      // Fallback for community Live2D packages without authored full-frame hand layers.
      const lockHand = state.mverMotionLockHand && state.mverMotionIndex >= 0;
      const leftDown = !lockHand && (anyBindingPressed(MVER_HAND_BINDINGS)
        || anyBindingPressed(MVER_LEFT_HAND_BINDINGS));
      const rightDown = !lockHand && anyBindingPressed(MVER_RIGHT_HAND_BINDINGS);
      renderer.setFrameInput('CatParamLeftHandDown', leftDown ? 1 : 0);
      renderer.setFrameInput('CatParamRightHandDown', rightDown ? 1 : 0);
      return;
    }
    // Hybrid controller-mode models often have no dedicated digital-button parameters at all.
    // Their generic CatParam*HandDown parameters are the intended visual press fallback. Merge
    // keyboard and controller sources here so a gamepad press remains visible even when there is
    // no South/East/... overlay image or explicit Cubism parameter.
    const gamepadLeft = HAS_CONTROLLER_STICK_PARAMETERS && isGamepadHandActive('left');
    const gamepadRight = HAS_CONTROLLER_STICK_PARAMETERS && isGamepadHandActive('right');
    renderer.setOverride('CatParamLeftHandDown', (state.leftKey || gamepadLeft) ? 1 : 0);
    renderer.setOverride('CatParamRightHandDown', (state.rightKey || gamepadRight) ? 1 : 0);
  }

  function setNormalizedFaceParameter(
    id, normalized, gain = 1, finalPass = true, owner = 'face', priority = 30,
  ) {
    if (!renderer) return;
    const range = renderer.range(id);
    if (!range) return;
    const n = Math.max(-1, Math.min(1, Number(normalized) || 0)) * gain;
    const value = n >= 0 ? n * Math.max(0, range[1]) : n * Math.max(0, -range[0]);
    renderer.setTrackingTarget(id, value, finalPass, owner, priority);
  }

  const LIVE2D_MOTION_PARAMETER_IDS = [
    // Yumi / VTube Studio style parameters adapted from the supplied model.
    'ParamBodyposX', 'ParamBodyposX2', 'ParamBodyposY',
    'ParamBodyAngleX2', 'ParamBodyAngleY2', 'ParamBodyAngleZ2',
    'Paramdown', 'Paramdown1',
    'ParamarmupL', 'ParamarmupR',
    // Common community full-body aliases. Missing parameters are ignored by the renderer.
    'ParamArmL', 'ParamArmR', 'ParamArmAngleL', 'ParamArmAngleR',
    'ParamShoulderL', 'ParamShoulderR',
    'ParamHandLX', 'ParamHandLY', 'ParamHandRX', 'ParamHandRY',
    'ParamHandX_L', 'ParamHandY_L', 'ParamHandX_R', 'ParamHandY_R',
    'ParamLegL', 'ParamLegR', 'ParamLegMoveL', 'ParamLegMoveR',
    'ParamFootL', 'ParamFootR',
  ];

  function setUnitMotionParameter(
    id, unit, gain = 1, finalPass = true, owner = 'expression', priority = 40,
  ) {
    if (!renderer) return;
    const range = renderer.range(id);
    if (!range) return;
    const u = Math.max(0, Math.min(1, Number(unit) || 0)) * gain;
    const zero = Math.max(range[0], Math.min(range[1], 0));
    const value = zero + (range[1] - zero) * Math.max(0, Math.min(1, u));
    renderer.setTrackingTarget(id, value, finalPass, owner, priority);
  }

  function setSignedMotionAliases(ids, value, gain = 1, finalPass = false) {
    for (const id of ids) setNormalizedFaceParameter(id, value, gain, finalPass, 'motion', 10);
  }

  function setUnitMotionAliases(ids, value, gain = 1, finalPass = false) {
    for (const id of ids) setUnitMotionParameter(id, value, gain, finalPass, 'motion', 10);
  }

  function applyLive2DMotionTracking(
    bodyX, bodyY, crouch, leftArmUp, rightArmUp,
    leftHandX, leftHandY, rightHandX, rightHandY,
    leftLegMotion, rightLegMotion, confidence,
  ) {
    if (!renderer || !AXON_LIVE2D_DISPLAY) return;
    const weight = 1;
    const bx = Math.max(-1, Math.min(1, Number(bodyX) || 0)) * weight;
    const by = Math.max(-1, Math.min(1, Number(bodyY) || 0)) * weight;
    const down = Math.max(0, Math.min(1, Number(crouch) || 0)) * weight;
    const armL = Math.max(0, Math.min(1, Number(leftArmUp) || 0)) * weight;
    const armR = Math.max(0, Math.min(1, Number(rightArmUp) || 0)) * weight;
    const handLX = Math.max(-1, Math.min(1, Number(leftHandX) || 0)) * weight;
    const handLY = Math.max(-1, Math.min(1, Number(leftHandY) || 0)) * weight;
    const handRX = Math.max(-1, Math.min(1, Number(rightHandX) || 0)) * weight;
    const handRY = Math.max(-1, Math.min(1, Number(rightHandY) || 0)) * weight;
    const legL = Math.max(0, Math.min(1, Number(leftLegMotion) || 0)) * weight;
    const legR = Math.max(0, Math.min(1, Number(rightLegMotion) || 0)) * weight;

    // Supplied yumi model: body translation/crouch + authored arm-up toggles + mouth/jaw.
    setSignedMotionAliases(['ParamBodyposX', 'ParamBodyposX2'], bx, 0.85);
    setSignedMotionAliases(['ParamBodyposY'], by, 0.75);
    setSignedMotionAliases(['ParamBodyAngleX2'], bx, 0.62);
    setSignedMotionAliases(['ParamBodyAngleY2'], by, 0.54);
    setSignedMotionAliases(['ParamBodyAngleZ2'], bx * by, 0.48);
    setUnitMotionAliases(['Paramdown', 'Paramdown1'], down, 1.0);
    setUnitMotionAliases(['ParamarmupL'], armL, 1.0);
    setUnitMotionAliases(['ParamarmupR'], armR, 1.0);

    // Community model aliases. These calls are no-ops when the imported model lacks the id.
    setSignedMotionAliases(['ParamArmL', 'ParamArmAngleL'], handLY, 0.85);
    setSignedMotionAliases(['ParamArmR', 'ParamArmAngleR'], handRY, 0.85);
    setUnitMotionAliases(['ParamShoulderL'], armL, 0.85);
    setUnitMotionAliases(['ParamShoulderR'], armR, 0.85);
    setSignedMotionAliases(['ParamHandLX', 'ParamHandX_L'], handLX, 0.90);
    setSignedMotionAliases(['ParamHandLY', 'ParamHandY_L'], handLY, 0.90);
    setSignedMotionAliases(['ParamHandRX', 'ParamHandX_R'], handRX, 0.90);
    setSignedMotionAliases(['ParamHandRY', 'ParamHandY_R'], handRY, 0.90);
    setUnitMotionAliases(['ParamLegL', 'ParamLegMoveL', 'ParamFootL'], legL, 0.80);
    setUnitMotionAliases(['ParamLegR', 'ParamLegMoveR', 'ParamFootR'], legR, 0.80);
  }

  function clearLive2DMotionTracking() {
    if (!renderer) return;
    for (const id of LIVE2D_MOTION_PARAMETER_IDS) renderer.clearTrackingTarget(id, 'motion');
  }

  function applyLive2DWatermarkVisibility(hidden) {
    AXON_LIVE2D_HIDE_WATERMARK = Boolean(hidden);
    if (!renderer) return;
    for (const id of AXON_LIVE2D_WATERMARK_PARAMETERS) {
      const range = renderer.range(id);
      if (!range) continue;
      // Most VTube Studio watermark toggles are additive expressions around zero. Explicit zero
      // is safer than minimum (-1 on some models), while falling back to min if zero is invalid.
      const off = range[0] <= 0 && range[1] >= 0 ? 0 : range[0];
      if (AXON_LIVE2D_HIDE_WATERMARK) renderer.setOverride(id, off);
      else renderer.clearOverride(id);
    }
    for (const id of AXON_LIVE2D_WATERMARK_PARTS) {
      const partIndex = renderer.partIndex.get(id);
      if (partIndex === undefined) continue;
      if (AXON_LIVE2D_HIDE_WATERMARK) {
        if (!AXON_WATERMARK_PART_BASE.has(partIndex)) {
          AXON_WATERMARK_PART_BASE.set(partIndex, renderer.model.parts.opacities[partIndex]);
        }
        renderer.model.parts.opacities[partIndex] = 0;
      } else if (AXON_WATERMARK_PART_BASE.has(partIndex)) {
        renderer.model.parts.opacities[partIndex] = AXON_WATERMARK_PART_BASE.get(partIndex);
        AXON_WATERMARK_PART_BASE.delete(partIndex);
      }
    }
  }

  function applyMouseAxisAliases(ids, ratio, transient) {
    if (!renderer || !Array.isArray(ids)) return;
    for (const idRaw of ids) {
      const id = String(idRaw || '');
      const range = renderer.range(id);
      if (!range) continue;
      const value = range[1] - Math.max(0, Math.min(1, Number(ratio) || 0)) * (range[1] - range[0]);
      if (transient) renderer.setFrameInput(id, value);
      else renderer.setOverride(id, value);
    }
  }

  function applyPointerOverrides(xRatio, yRatio) {
    if (!renderer) return;

    if (IS_MVER) {
      // Mver's stock Cubism drag drives Angle/Body/Eye, while many community models also
      // wire custom ParamMouseX/ParamMouseY directly into physics (feet, skirt, ears, etc.).
      // Feed both paths every frame. This is transient input and must never be saved as the
      // model's persistent base state.
      let effectiveX = Math.max(0, Math.min(1, Number(xRatio) || 0));
      const effectiveY = Math.max(0, Math.min(1, Number(yRatio) || 0));
      if (MVER_LEFT_HANDED) effectiveX = 1 - effectiveX;
      if (state.globalReverse) effectiveX = 1 - effectiveX;

      const dragX = Math.max(-1, Math.min(1, 1 - 2 * effectiveX));
      const dragY = Math.max(-1, Math.min(1, 1 - 2 * effectiveY));
      renderer.setDragTarget(dragX, dragY);

      const mouseXRange = renderer.range('ParamMouseX');
      if (mouseXRange) {
        renderer.setFrameInput('ParamMouseX', mouseXRange[1] - effectiveX * (mouseXRange[1] - mouseXRange[0]));
      }
      const mouseYRange = renderer.range('ParamMouseY');
      if (mouseYRange) {
        renderer.setFrameInput('ParamMouseY', mouseYRange[1] - effectiveY * (mouseYRange[1] - mouseYRange[0]));
      }
      applyMouseAxisAliases(MOUSE_X_PARAMETER_IDS, effectiveX, true);
      applyMouseAxisAliases(MOUSE_Y_PARAMETER_IDS, effectiveY, true);
      return;
    }

    const ids = [
      'ParamMouseX', 'ParamMouseY', 'ParamAngleX', 'ParamAngleY', 'ParamAngleZ',
      'ParamEyeBallX', 'ParamEyeBallY',
    ];
    for (const id of ids) {
      const range = renderer.range(id);
      if (!range) continue;
      const [min, max] = range;
      let value;
      if (id.endsWith('Z')) {
        const dragX = 1 - 2 * xRatio;
        const dragY = 1 - 2 * yRatio;
        value = dragX * dragY * min;
      } else {
        let ratio = id.endsWith('X') ? xRatio : yRatio;
        if (state.globalReverse && id === 'ParamMouseX') ratio = 1 - ratio;
        value = max - ratio * (max - min);
      }
      renderer.setOverride(id, value);
    }
    let effectiveX = Math.max(0, Math.min(1, Number(xRatio) || 0));
    if (state.globalReverse) effectiveX = 1 - effectiveX;
    applyMouseAxisAliases(MOUSE_X_PARAMETER_IDS, effectiveX, false);
    applyMouseAxisAliases(MOUSE_Y_PARAMETER_IDS, yRatio, false);
  }

  function syncGlobalReverse() {
    // Do not mirror the stage: the cat and background must remain unchanged.
    // Only input mapping and horizontal mouse response are reversed.
    if (renderer && state.pointerActive) {
      applyPointerOverrides(state.cursorX, state.cursorY);
    }
    if (IS_MVER) {
      // Existing held sprite keys are not re-bound mid-press; new presses use the reversed mapping.
      syncMverMouseVisual();
    }
  }

  function clearLive2DMouseTracking() {
    state.cursorX = state.targetX = 0.5;
    state.cursorY = state.targetY = 0.5;
    state.pointerActive = false;
    if (!renderer) return;
    renderer.setDragTarget(0, 0);
    renderer.clearFrameInput('ParamMouseX');
    renderer.clearFrameInput('ParamMouseY');
    for (const id of MOUSE_X_PARAMETER_IDS) renderer.clearFrameInput(String(id));
    for (const id of MOUSE_Y_PARAMETER_IDS) renderer.clearFrameInput(String(id));
    // Non-Mver fallback uses persistent overrides for pointer tracking; release only the pointer
    // parameters here so face/expression/motion capture can immediately regain control.
    if (!IS_MVER) {
      for (const id of [
        'ParamMouseX', 'ParamMouseY', 'ParamAngleX', 'ParamAngleY', 'ParamAngleZ',
        'ParamEyeBallX', 'ParamEyeBallY',
      ]) renderer.clearOverride(id);
    }
  }

  function applyMouseDelta(dx, dy, screenWidth, screenHeight) {
    let width = Math.max(1, Number(screenWidth) || DESIGN_WIDTH);
    let height = Math.max(1, Number(screenHeight) || DESIGN_HEIGHT);
    // Mver workarea stores the Windows desktop capture rectangle. On Android we receive relative
    // hardware motion (REL_X / REL_Y) and the actual Android display size from Java, so applying a
    // foreign Windows workarea here makes cursor movement collapse or jump on imported packs.
    const moveX = (Number(dx) || 0) * (IS_MVER ? MVER_MOUSE_SPEED : 1);
    const moveY = (Number(dy) || 0) * (IS_MVER ? MVER_MOUSE_SPEED : 1);
    if (moveX === 0 && moveY === 0) return;
    state.targetX = Math.max(0, Math.min(1, state.targetX + moveX / width));
    state.targetY = Math.max(0, Math.min(1, state.targetY + moveY / height));
    state.pointerActive = true;
  }

  function applyMouseButtons(mask, pulseMask = 0) {
    const next = (Number(mask) || 0) & 3;
    const pulses = (Number(pulseMask) || 0) & 3;
    const now = performance.now();
    state.mouseButtons = next;
    for (let index = 0; index < 2; index++) {
      const bit = 1 << index;
      if ((next & bit) || (pulses & bit)) {
        state.mouseVisualButtons |= bit;
        state.mousePressUntil[index] = Math.max(state.mousePressUntil[index], now + MIN_MOUSE_PRESS_MS);
      }
    }
  }

  function updateMouseButtonVisual(now) {
    const before = state.mouseVisualButtons;
    for (let index = 0; index < 2; index++) {
      const bit = 1 << index;
      if (state.mouseButtons & bit) {
        state.mouseVisualButtons |= bit;
      } else if (now >= state.mousePressUntil[index]) {
        state.mouseVisualButtons &= ~bit;
      }
    }
    return before !== state.mouseVisualButtons;
  }

  const nativePolledKeys = new Set();
  const keyParameterIdCache = new Map();
  const functionGroupParameterIds = new Map();
  for (const item of PARAMETER_DEFINITIONS) {
    if (!item || String(item.category || '') !== 'function' || !item.id) continue;
    const group = String(item.groupId || item.group || '');
    if (!group) continue;
    let ids = functionGroupParameterIds.get(group);
    if (!ids) { ids = []; functionGroupParameterIds.set(group, ids); }
    const id = String(item.id);
    if (!ids.includes(id)) ids.push(id);
  }

  function keyParameterIds(rawKey) {
    const key = String(rawKey || '');
    const cached = keyParameterIdCache.get(key);
    if (cached) return cached;
    const candidates = [key];
    if (key.startsWith('Shift')) candidates.push('Shift');
    if (key.startsWith('Control')) candidates.push('Control');
    if (key === 'AltGr') candidates.push('Alt');
    const result = [];
    const seen = new Set();
    for (const candidate of candidates) {
      const ids = Array.isArray(KEY_PARAMETER_MAP[candidate]) ? KEY_PARAMETER_MAP[candidate] : [];
      for (const rawId of ids) {
        const id = String(rawId);
        if (!id || seen.has(id)) continue;
        seen.add(id);
        result.push(id);
      }
    }
    keyParameterIdCache.set(key, result);
    return result;
  }

  function setFrameActiveIds(ids, active) {
    if (!renderer || !Array.isArray(ids)) return;
    for (const id of ids) renderer.setFrameActive(String(id), Boolean(active));
  }

  function syncModernKeyParameters(rawKey, pressed) {
    if (!renderer) return;
    const key = String(rawKey || '');
    const wasPressed = state.modernPressed.has(key);
    if (wasPressed === Boolean(pressed)) return;
    if (pressed) state.modernPressed.add(key);
    else state.modernPressed.delete(key);

    // Keep a reference count per authored parameter. ShiftLeft/ShiftRight (and similar aliases)
    // can target one Cubism parameter; releasing one source must not clear the other source.
    for (const id of keyParameterIds(key)) {
      const before = state.modernParameterCounts.get(id) || 0;
      const after = Math.max(0, before + (pressed ? 1 : -1));
      if (after > 0) state.modernParameterCounts.set(id, after);
      else state.modernParameterCounts.delete(id);
      if ((before > 0) !== (after > 0)) renderer.setFrameActive(id, after > 0);
    }
    setFrameActiveIds(KEYBOARD_DOWN_PARAMETER_IDS, state.modernPressed.size > 0);
  }

  const gamepadParameterIdCache = new Map();
  const HAS_CONTROLLER_STICK_PARAMETERS = PARAMETER_DEFINITIONS.some((item) => {
    const id = String((item && item.id) || '');
    return id === 'CatParamStickLX' || id === 'CatParamStickLY'
      || id === 'CatParamStickRX' || id === 'CatParamStickRY';
  });

  function normalizeControllerToken(raw) {
    return String(raw || '').trim().toLowerCase().replace(/[\s_\-]/g, '');
  }

  function canonicalGamepadSemantic(raw) {
    const token = normalizeControllerToken(raw);
    if (['south', 'a', 'buttona', 'btna', 'cross'].includes(token)) return 'South';
    if (['east', 'b', 'buttonb', 'btnb', 'circle'].includes(token)) return 'East';
    if (['west', 'x', 'buttonx', 'btnx', 'square'].includes(token)) return 'West';
    if (['north', 'y', 'buttony', 'btny', 'triangle'].includes(token)) return 'North';
    if (['l1', 'lb', 'leftbumper', 'leftshoulder', 'lefttrigger'].includes(token)) return 'LeftTrigger';
    if (['r1', 'rb', 'rightbumper', 'rightshoulder', 'righttrigger'].includes(token)) return 'RightTrigger';
    if (['l2', 'lt', 'lefttrigger2'].includes(token)) return 'LeftTrigger2';
    if (['r2', 'rt', 'righttrigger2'].includes(token)) return 'RightTrigger2';
    if (['l3', 'ls', 'leftstick', 'leftstickclick', 'leftthumb'].includes(token)) return 'L3';
    if (['r3', 'rs', 'rightstick', 'rightstickclick', 'rightthumb'].includes(token)) return 'R3';
    if (['select', 'back', 'view'].includes(token)) return 'Select';
    if (['start', 'menu', 'options'].includes(token)) return 'Start';
    if (['mode', 'guide', 'home', 'ps'].includes(token)) return 'Mode';
    if (['dpadup', 'up', 'hatup'].includes(token)) return 'DPadUp';
    if (['dpaddown', 'down', 'hatdown'].includes(token)) return 'DPadDown';
    if (['dpadleft', 'left', 'hatleft'].includes(token)) return 'DPadLeft';
    if (['dpadright', 'right', 'hatright'].includes(token)) return 'DPadRight';
    if (['m1', 'back1', 'paddle1'].includes(token)) return 'M1';
    if (['m2', 'back2', 'paddle2'].includes(token)) return 'M2';
    if (['m3', 'back3', 'paddle3'].includes(token)) return 'M3';
    if (['m4', 'back4', 'paddle4'].includes(token)) return 'M4';
    if (token === 'c') return 'C';
    if (token === 'z') return 'Z';
    return String(raw || '');
  }

  function gamepadSemanticAliases(raw) {
    const canonical = canonicalGamepadSemantic(raw);
    const aliases = {
      South: ['South', 'A', 'ButtonA', 'BtnA', 'Cross'],
      East: ['East', 'B', 'ButtonB', 'BtnB', 'Circle'],
      West: ['West', 'X', 'ButtonX', 'BtnX', 'Square'],
      North: ['North', 'Y', 'ButtonY', 'BtnY', 'Triangle'],
      LeftTrigger: ['LeftTrigger', 'LeftShoulder', 'LeftBumper', 'L1', 'LB'],
      RightTrigger: ['RightTrigger', 'RightShoulder', 'RightBumper', 'R1', 'RB'],
      LeftTrigger2: ['LeftTrigger2', 'L2', 'LT'],
      RightTrigger2: ['RightTrigger2', 'R2', 'RT'],
      L3: ['L3', 'LS', 'LeftStick', 'LeftStickClick', 'LeftThumb'],
      R3: ['R3', 'RS', 'RightStick', 'RightStickClick', 'RightThumb'],
      Select: ['Select', 'Back', 'View'],
      Start: ['Start', 'Menu', 'Options'],
      Mode: ['Mode', 'Guide', 'Home', 'PS'],
      DPadUp: ['DPadUp', 'Up', 'HatUp'],
      DPadDown: ['DPadDown', 'Down', 'HatDown'],
      DPadLeft: ['DPadLeft', 'Left', 'HatLeft'],
      DPadRight: ['DPadRight', 'Right', 'HatRight'],
      M1: ['M1', 'Back1', 'Paddle1'], M2: ['M2', 'Back2', 'Paddle2'],
      M3: ['M3', 'Back3', 'Paddle3'], M4: ['M4', 'Back4', 'Paddle4'],
      C: ['C'], Z: ['Z'],
    };
    return aliases[canonical] || [canonical];
  }

  function definitionMatchesGamepadSemantic(item, canonical) {
    if (!item || !item.id) return false;
    const id = normalizeControllerToken(item.id);
    const name = normalizeControllerToken(item.name);
    const group = normalizeControllerToken(item.group || item.groupId);
    const combined = `${id}${name}${group}`;
    const controllerContext = group.includes('手柄') || group.includes('控制器')
      || group.includes('gamepad') || group.includes('controller') || group.includes('joystick')
      || id.includes('gamepad') || id.includes('controller') || id.includes('joystick')
      || id.includes('button') || id.includes('btn') || id.startsWith('catparamstick');
    const bare = (values) => controllerContext && values.includes(name);
    switch (canonical) {
      case 'South': return combined.includes('buttona') || combined.includes('btna') || combined.includes('south') || combined.includes('cross') || bare(['a', '按键a', '按钮a']);
      case 'East': return combined.includes('buttonb') || combined.includes('btnb') || combined.includes('east') || combined.includes('circle') || bare(['b', '按键b', '按钮b']);
      case 'West': return combined.includes('buttonx') || combined.includes('btnx') || combined.includes('west') || combined.includes('square') || bare(['x', '按键x', '按钮x']);
      case 'North': return combined.includes('buttony') || combined.includes('btny') || combined.includes('north') || combined.includes('triangle') || bare(['y', '按键y', '按钮y']);
      case 'LeftTrigger': return combined.includes('leftbumper') || combined.includes('leftshoulder') || combined.includes('buttonl1') || combined.includes('btnl1') || combined.includes('左肩键') || bare(['l1', 'lb']);
      case 'RightTrigger': return combined.includes('rightbumper') || combined.includes('rightshoulder') || combined.includes('buttonr1') || combined.includes('btnr1') || combined.includes('右肩键') || bare(['r1', 'rb']);
      case 'LeftTrigger2': return combined.includes('lefttrigger2') || combined.includes('buttonl2') || combined.includes('btnl2') || combined.includes('左扳机') || combined.includes('左触发') || bare(['l2', 'lt']);
      case 'RightTrigger2': return combined.includes('righttrigger2') || combined.includes('buttonr2') || combined.includes('btnr2') || combined.includes('右扳机') || combined.includes('右触发') || bare(['r2', 'rt']);
      case 'L3': return combined.includes('leftstickdown') || combined.includes('leftstickclick') || combined.includes('buttonl3') || combined.includes('btnl3') || combined.includes('左摇杆按下') || bare(['l3', 'ls']);
      case 'R3': return combined.includes('rightstickdown') || combined.includes('rightstickclick') || combined.includes('buttonr3') || combined.includes('btnr3') || combined.includes('右摇杆按下') || bare(['r3', 'rs']);
      case 'DPadUp': return combined.includes('dpadup') || combined.includes('hatup') || combined.includes('十字键上') || combined.includes('方向键上');
      case 'DPadDown': return combined.includes('dpaddown') || combined.includes('hatdown') || combined.includes('十字键下') || combined.includes('方向键下');
      case 'DPadLeft': return combined.includes('dpadleft') || combined.includes('hatleft') || combined.includes('十字键左') || combined.includes('方向键左');
      case 'DPadRight': return combined.includes('dpadright') || combined.includes('hatright') || combined.includes('十字键右') || combined.includes('方向键右');
      case 'Select': return combined.includes('select') || combined.includes('buttonback') || combined.includes('view') || combined.includes('选择键');
      case 'Start': return combined.includes('start') || combined.includes('menu') || combined.includes('options') || combined.includes('开始键');
      case 'Mode': return combined.includes('guide') || combined.includes('mode') || combined.includes('home') || combined.includes('ps键');
      case 'M1': return combined.includes('buttonm1') || combined.includes('btnm1') || combined.includes('back1') || combined.includes('paddle1') || bare(['m1']);
      case 'M2': return combined.includes('buttonm2') || combined.includes('btnm2') || combined.includes('back2') || combined.includes('paddle2') || bare(['m2']);
      case 'M3': return combined.includes('buttonm3') || combined.includes('btnm3') || combined.includes('back3') || combined.includes('paddle3') || bare(['m3']);
      case 'M4': return combined.includes('buttonm4') || combined.includes('btnm4') || combined.includes('back4') || combined.includes('paddle4') || bare(['m4']);
      default: return false;
    }
  }

  function gamepadParameterIds(rawKey) {
    const key = String(rawKey || '');
    const cached = gamepadParameterIdCache.get(key);
    if (cached) return cached;
    const canonical = canonicalGamepadSemantic(key);
    const result = [];
    const seen = new Set();
    for (const alias of gamepadSemanticAliases(canonical)) {
      for (const rawId of (Array.isArray(KEY_PARAMETER_MAP[alias]) ? KEY_PARAMETER_MAP[alias] : [])) {
        const id = String(rawId || '');
        if (id && !seen.has(id)) { seen.add(id); result.push(id); }
      }
    }
    // Old imported manifests can lack the canonical key map entirely. Re-scan the CDI-derived
    // parameter definitions in the Web runtime so the user does not have to re-import the style.
    for (const item of PARAMETER_DEFINITIONS) {
      if (!definitionMatchesGamepadSemantic(item, canonical)) continue;
      const id = String(item.id || '');
      if (id && !seen.has(id)) { seen.add(id); result.push(id); }
    }

    // Some BongoCat "standard mode" packages (including 大月下) do not author dedicated
    // ButtonA/ButtonB/... parameters. They switch the artwork into controller mode with a
    // model-function parameter, keep CatParamStick* for analog motion, and deliberately reuse
    // the keyboard A/B/X/Y parameters for the face buttons. Only enable this fallback when the
    // model actually exposes controller-stick parameters, so ordinary keyboard models never
    // reinterpret a physical gamepad A as keyboard A.
    if (result.length === 0 && HAS_CONTROLLER_STICK_PARAMETERS) {
      const keyboardFallback = {
        South: 'KeyA', East: 'KeyB', West: 'KeyX', North: 'KeyY',
        DPadUp: 'UpArrow', DPadDown: 'DownArrow',
        DPadLeft: 'LeftArrow', DPadRight: 'RightArrow',
      }[canonical];
      if (keyboardFallback) {
        for (const rawId of keyParameterIds(keyboardFallback)) {
          const id = String(rawId || '');
          if (id && !seen.has(id)) { seen.add(id); result.push(id); }
        }
      }
    }
    gamepadParameterIdCache.set(key, result);
    return result;
  }

  function gamepadHandSide(rawKey) {
    const key = canonicalGamepadSemantic(rawKey);
    if (['DPadUp','DPadDown','DPadLeft','DPadRight','LeftTrigger','LeftTrigger2','L3','Select','M1','M3'].includes(key)) return 'left';
    if (['South','East','West','North','C','Z','RightTrigger','RightTrigger2','R3','Start','Mode','M2','M4'].includes(key)) return 'right';
    return '';
  }

  function isGamepadHandActive(side) {
    for (const key of state.modernGamepadPressed) {
      if (gamepadHandSide(key) === side) return true;
    }
    return false;
  }

  function syncModernGamepadParameters(rawKey, pressed) {
    if (!renderer) return;
    const key = canonicalGamepadSemantic(rawKey);
    const wasPressed = state.modernGamepadPressed.has(key);
    if (wasPressed === Boolean(pressed)) return;
    if (pressed) state.modernGamepadPressed.add(key);
    else state.modernGamepadPressed.delete(key);

    // Share the parameter reference counter with keyboard semantics so two physical sources that
    // intentionally target the same authored Cubism parameter cannot release each other early.
    for (const id of gamepadParameterIds(key)) {
      const before = state.modernParameterCounts.get(id) || 0;
      const after = Math.max(0, before + (pressed ? 1 : -1));
      if (after > 0) state.modernParameterCounts.set(id, after);
      else state.modernParameterCounts.delete(id);
      if ((before > 0) !== (after > 0)) renderer.setFrameActive(id, after > 0);
    }
  }

  function applySavedParameterValues() {
    if (!renderer) return;
    for (const [id, raw] of Object.entries(SAVED_PARAMETER_VALUES)) {
      const ratio = Number(raw);
      if (!Number.isFinite(ratio)) continue;
      renderer.setNormalizedOverride(String(id), Math.max(0, Math.min(1, ratio)));
      state.parameterToggles.set(String(id), false);
    }
  }

  function restoreSavedParameter(id) {
    if (!renderer) return;
    const parameterId = String(id || '');
    if (Object.prototype.hasOwnProperty.call(SAVED_PARAMETER_VALUES, parameterId)) {
      const ratio = Number(SAVED_PARAMETER_VALUES[parameterId]);
      if (Number.isFinite(ratio)) {
        renderer.setNormalizedOverride(parameterId, Math.max(0, Math.min(1, ratio)));
        state.parameterToggles.set(parameterId, false);
        return;
      }
    }
    renderer.clearOverride(parameterId);
    state.parameterToggles.set(parameterId, false);
  }

  function queueParameterSequence(ids) {
    if (!renderer || !Array.isArray(ids)) return false;
    const seen = new Set();
    const unique = [];
    for (const rawId of ids) {
      const id = String(rawId || '');
      if (!id || seen.has(id) || !renderer.range(id) || renderer.isParameterLocked(id)) continue;
      seen.add(id);
      unique.push(id);
    }
    if (!unique.length) return false;

    // Cancel stale pulse/sequence restores for the same parameters. The previous implementation
    // filtered by the *new* generation id (which could never match old events), so repeatedly
    // triggering a transformation could let an old restore event interrupt the new sequence.
    const affected = new Set(unique);
    state.parameterSequenceEvents = state.parameterSequenceEvents.filter((item) => !affected.has(item.id));
    for (const id of unique) {
      state.parameterPulseUntil.delete(id);
      state.parameterPreviewRestoreToggle.delete(id);
      restoreSavedParameter(id);
    }

    const now = performance.now();
    const generation = ++state.parameterSequenceGeneration;
    unique.forEach((id, index) => {
      state.parameterSequenceEvents.push({ at: now + index * 90, id, active: true, generation });
    });
    const restoreAt = now + Math.max(620, unique.length * 90 + 360);
    unique.forEach((id) => {
      state.parameterSequenceEvents.push({ at: restoreAt, id, active: false, generation });
    });
    return true;
  }

  function processParameterSequences(now) {
    if (!renderer || !state.parameterSequenceEvents.length) return;
    const events = state.parameterSequenceEvents;
    let write = 0;
    for (let read = 0; read < events.length; read++) {
      const event = events[read];
      if (now < event.at) {
        events[write++] = event;
        continue;
      }
      if (event.active) {
        renderer.setNormalizedOverride(event.id, 1);
        state.parameterToggles.set(event.id, true);
      } else {
        restoreSavedParameter(event.id);
      }
    }
    events.length = write;
  }

  function triggerParameter(id, mode, pressed) {
    if (!renderer) return false;
    const parameterId = String(id || '');
    if (!renderer.range(parameterId) || renderer.isParameterLocked(parameterId)) return false;
    const trigger = String(mode || 'toggle').toLowerCase();
    state.parameterPreviewRestoreToggle.delete(parameterId);
    if (trigger === 'hold') {
      if (pressed) {
        state.parameterPulseUntil.delete(parameterId);
        state.parameterSequenceEvents = state.parameterSequenceEvents.filter((event) => event.id !== parameterId);
        renderer.setNormalizedOverride(parameterId, 1);
        state.parameterToggles.set(parameterId, true);
      } else {
        restoreSavedParameter(parameterId);
      }
      return true;
    }
    if (!pressed) return true;
    if (trigger === 'pulse') {
      state.parameterSequenceEvents = state.parameterSequenceEvents.filter((event) => event.id !== parameterId);
      renderer.setNormalizedOverride(parameterId, 1);
      state.parameterToggles.set(parameterId, true);
      state.parameterPulseUntil.set(parameterId, performance.now() + 520);
      return true;
    }
    if (trigger === 'sequence') return queueParameterSequence([parameterId]);
    state.parameterPulseUntil.delete(parameterId);
    state.parameterSequenceEvents = state.parameterSequenceEvents.filter((event) => event.id !== parameterId);
    const next = !Boolean(state.parameterToggles.get(parameterId));
    if (next) {
      state.parameterToggles.set(parameterId, true);
      renderer.setNormalizedOverride(parameterId, 1);
    } else {
      restoreSavedParameter(parameterId);
    }
    return true;
  }

  /**
   * UI-only model action preview. Parameter actions are always pulsed so pressing "Test"
   * cannot leave a toggle latched or require a synthetic key-up later. Runtime hotkeys still use
   * triggerModelFunction() and preserve their authored hold/toggle/pulse/sequence semantics.
   */
  function previewModelFunction(token, mode) {
    const raw = String(token || '');
    if (raw.startsWith('param:')) {
      const id = raw.slice(6);
      if (!renderer || !renderer.range(id) || renderer.isParameterLocked(id)) return false;
      const trigger = String(mode || 'toggle').toLowerCase();
      if (trigger === 'sequence') return queueParameterSequence([id]);
      state.parameterSequenceEvents = state.parameterSequenceEvents.filter((event) => event.id !== id);
      state.parameterPreviewRestoreToggle.set(id, Boolean(state.parameterToggles.get(id)));
      if (!renderer.setNormalizedOverride(id, 1)) {
        state.parameterPreviewRestoreToggle.delete(id);
        return false;
      }
      state.parameterToggles.set(id, true);
      state.parameterPulseUntil.set(id, performance.now() + 900);
      return true;
    }
    if (raw.startsWith('sequence:')) {
      return queueParameterSequence(groupedSequenceParameterIds(raw.slice(9)));
    }
    return triggerModelFunction(raw, mode, true);
  }

  function groupedSequenceParameterIds(groupKey) {
    return functionGroupParameterIds.get(String(groupKey || '')) || [];
  }

  function clampPhysicsStrength(value) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? Math.max(0, Math.min(2, parsed)) : 1;
  }

  function normalizePhysicsControls(raw) {
    const source = raw && typeof raw === 'object' ? raw : {};
    const groupsIn = source.groups && typeof source.groups === 'object' ? source.groups : {};
    const groups = Object.create(null);
    for (const key of Object.keys(groupsIn)) {
      const item = groupsIn[key] && typeof groupsIn[key] === 'object' ? groupsIn[key] : {};
      groups[String(key)] = {
        enabled: item.enabled !== false,
        strength: clampPhysicsStrength(item.strength),
      };
    }
    return {
      globalStrength: clampPhysicsStrength(source.globalStrength),
      groups,
    };
  }

  function buildPhysicsGroupKeys(physics) {
    const settings = physics && Array.isArray(physics.PhysicsSettings) ? physics.PhysicsSettings : [];
    const seen = new Set();
    const result = [];
    for (let i = 0; i < settings.length; i++) {
      const setting = settings[i] || {};
      const id = String(setting.Id || setting.id || '').trim();
      const name = String(setting.Name || setting.name || '').trim();
      let key = id || name || `physics-${i}`;
      if (seen.has(key)) key = `${key}#${i}`;
      seen.add(key);
      result.push(key);
    }
    return result;
  }

  function physicsControlFor(index) {
    const key = PHYSICS_GROUP_KEYS[index] || `physics-${index}`;
    const item = physicsControls.groups[key];
    return {
      key,
      enabled: !item || item.enabled !== false,
      strength: item ? clampPhysicsStrength(item.strength) : 1,
    };
  }

  function physicsEnabledTopologyChanged(previous, next) {
    for (const key of PHYSICS_GROUP_KEYS) {
      const before = previous && previous.groups ? previous.groups[key] : null;
      const after = next && next.groups ? next.groups[key] : null;
      const beforeEnabled = !before || before.enabled !== false;
      const afterEnabled = !after || after.enabled !== false;
      if (beforeEnabled !== afterEnabled) return true;
    }
    return false;
  }

  function beginTemporaryExpression(kind, index) {
    if (state.functionExpressionUntil <= 0) {
      state.functionExpressionRestoreKind = state.debugExpressionKind;
      state.functionExpressionRestoreIndex = state.debugExpressionIndex;
      state.functionExpressionRestoreStartedAt = state.debugExpressionStartedAt;
    }
    state.debugExpressionKind = kind;
    state.debugExpressionIndex = index;
    state.debugExpressionStartedAt = performance.now();
    state.functionExpressionUntil = performance.now() + 1400;
  }

  function triggerModelFunction(token, mode, pressed) {
    const raw = String(token || '');
    if (raw.startsWith('param:')) return triggerParameter(raw.slice(6), mode, pressed);
    if (!pressed) return true;
    if (raw.startsWith('sequence:')) {
      return queueParameterSequence(groupedSequenceParameterIds(raw.slice(9)));
    }
    if (raw.startsWith('expression:')) {
      const index = Number(raw.slice(11)) | 0;
      if (index < 0 || index >= MVER_EXPRESSIONS.length) return false;
      beginTemporaryExpression('live2d', index);
      return true;
    }
    if (raw.startsWith('face:')) {
      const index = Number(raw.slice(5)) | 0;
      if (!IS_MVER || index < 0) return false;
      beginTemporaryExpression('face', index);
      syncMverFaceAndExpressions();
      return true;
    }
    if (raw.startsWith('motion:')) {
      const index = Number(raw.slice(7)) | 0;
      startMverMotion(index, false);
      return index >= 0 && index < MVER_MOTIONS.length;
    }
    return false;
  }

  window.AxonBongoCat = {
    key(key, pressed) {
      state.inputCount += 1;
      state.lastInput = `key:${String(key || '')}:${Boolean(pressed)}`;
      syncModernKeyParameters(String(key || ''), Boolean(pressed));
      if (IS_MVER) {
        setMverKey(String(key || ''), Boolean(pressed), false);
        return;
      }
      const rawValue = resolveSupportedKey(String(key || ''));
      if (!rawValue) return;
      const rawSide = keySide(rawValue);
      const value = inputKey(rawValue);
      if (!value) return;
      const side = keySide(value) || rawSide;

      // Reverse the input target, not the cat/background canvas.
      if (pressed) {
        if (side === 'left') state.leftKey = value;
        else state.rightKey = value;
        setOverlay(side, value);
      } else if (side === 'left' && state.leftKey === value) {
        state.leftKey = null;
        setOverlay(side, null);
      } else if (side === 'right' && state.rightKey === value) {
        state.rightKey = null;
        setOverlay(side, null);
      }

      syncHandOverrides();
    },

    gamepadButton(name, pressed) {
      const semantic = canonicalGamepadSemantic(String(name || ''));
      state.inputCount += 1;
      state.lastInput = `gamepad:${semantic}:${Boolean(pressed)}`;
      // Always feed Cubism parameters, including Mver-derived Live2D packages. Older builds
      // returned early for IS_MVER, which made sticks appear functional while every digital
      // controller button was silently discarded.
      syncModernGamepadParameters(semantic, Boolean(pressed));
      // Always update generic hand fallback before looking for optional raster button overlays.
      // The previous early return meant controller-mode Live2D packages with no South.png etc.
      // received the button edge internally but showed no visible hand movement.
      syncHandOverrides();
      if (IS_MVER) {
        setMverKey(semantic, Boolean(pressed), true);
        return;
      }
      const value = resolveSupportedKey(semantic);
      if (!value) return;
      const side = keySide(value);
      if (!side) return;
      if (pressed) {
        if (side === 'left') state.leftKey = value;
        else state.rightKey = value;
        setOverlay(side, value);
      } else if (side === 'left' && state.leftKey === value) {
        state.leftKey = null; setOverlay(side, null);
      } else if (side === 'right' && state.rightKey === value) {
        state.rightKey = null; setOverlay(side, null);
      }
      syncHandOverrides();
    },

    gamepadAxes(lx, ly, rx, ry, leftDown, rightDown) {
      if (!renderer) return;
      // Hybrid community models can expose a normal/keyboard skin and switch to controller mode
      // through a model function. Do not gate stick input solely on the imported style label when
      // the Cubism model itself contains stick parameters.
      const hasStickParameters = ['CatParamStickLX', 'CatParamStickLY', 'CatParamStickRX', 'CatParamStickRY']
        .some((id) => Boolean(renderer.range(id)));
      if (STYLE_MODE !== 'gamepad' && !hasStickParameters) return;
      const values = [
        ['CatParamStickLX', Number(lx) || 0], ['CatParamStickLY', Number(ly) || 0],
        ['CatParamStickRX', Number(rx) || 0], ['CatParamStickRY', Number(ry) || 0],
      ];
      for (const [id, raw] of values) {
        const range = renderer.range(id);
        if (!range) continue;
        const normalized = Math.max(-1, Math.min(1, raw));
        const value = normalized >= 0 ? normalized * range[1] : (-normalized) * range[0];
        renderer.setOverride(id, value);
      }
      const lActive = Math.abs(Number(lx)||0) > 0.04 || Math.abs(Number(ly)||0) > 0.04 || Boolean(leftDown);
      const rActive = Math.abs(Number(rx)||0) > 0.04 || Math.abs(Number(ry)||0) > 0.04 || Boolean(rightDown);
      renderer.setOverride('CatParamStickShowLeftHand', lActive ? 1 : 0);
      renderer.setOverride('CatParamStickShowRightHand', rActive ? 1 : 0);
      renderer.setOverride('CatParamStickLeftDown', leftDown ? 1 : 0);
      renderer.setOverride('CatParamStickRightDown', rightDown ? 1 : 0);
    },

    mouseButtons(mask) {
      const next = (Number(mask) || 0) & 3;
      const rising = next & ~state.mouseButtons;
      applyMouseButtons(next, rising);
    },

    mouseDelta(dx, dy, screenWidth, screenHeight) {
      applyMouseDelta(dx, dy, screenWidth, screenHeight);
    },

    clearMouseTracking() {
      clearLive2DMouseTracking();
    },

    mouseFrame(mask, pulseMask, dx, dy, screenWidth, screenHeight) {
      state.inputCount += 1;
      state.lastInput = `mouse:${Number(mask) || 0}:${Number(dx) || 0},${Number(dy) || 0}`;
      applyMouseButtons(mask, pulseMask);
      applyMouseDelta(dx, dy, screenWidth, screenHeight);
    },

    pointerRatio(x, y) {
      state.targetX = Math.max(0, Math.min(1, Number(x) || 0));
      state.targetY = Math.max(0, Math.min(1, Number(y) || 0));
      state.pointerActive = true;
    },

    setModelParameter(id, normalized) {
      if (!renderer) return false;
      const parameterId = String(id || '');
      const ratio = Math.max(0, Math.min(1, Number(normalized) || 0));
      // A manual debug adjustment becomes the new authoritative baseline. Cancel any
      // older pulse/sequence restore for this parameter first so it cannot snap the
      // slider back a few frames later.
      state.parameterPulseUntil.delete(parameterId);
      state.parameterPreviewRestoreToggle.delete(parameterId);
      state.parameterSequenceEvents = state.parameterSequenceEvents.filter((event) => event.id !== parameterId);
      state.parameterToggles.set(parameterId, false);
      if (!renderer.setNormalizedOverride(parameterId, ratio)) return false;
      SAVED_PARAMETER_VALUES[parameterId] = ratio;
      return true;
    },

    resetModelParameter(id) {
      const parameterId = String(id || '');
      delete SAVED_PARAMETER_VALUES[parameterId];
      state.parameterToggles.delete(parameterId);
      state.parameterPulseUntil.delete(parameterId);
      state.parameterPreviewRestoreToggle.delete(parameterId);
      state.parameterSequenceEvents = state.parameterSequenceEvents.filter((event) => event.id !== parameterId);
      if (renderer) renderer.clearOverride(parameterId);
    },

    setParameterLock(id, normalized) {
      if (!renderer) return false;
      return renderer.setNormalizedLock(String(id || ''), normalized);
    },

    clearParameterLock(id) {
      if (!renderer) return false;
      return renderer.clearLock(String(id || ''));
    },

    parameterDebug(id) {
      return renderer ? renderer.parameterDebug(String(id || '')) : null;
    },

    triggerModelFunction(token, mode, pressed) {
      return triggerModelFunction(token, mode, Boolean(pressed));
    },

    testModelFunction(token, mode) {
      return previewModelFunction(token, mode);
    },

    modelCapabilities() {
      return {
        parameters: PARAMETER_DEFINITIONS,
        expressions: MVER_EXPRESSIONS.length,
        motions: MVER_MOTIONS.map((item, index) => ({ index, name: String(item && item.name || `motion-${index}`) })),
        physics: Boolean(MVER_PHYSICS),
        keyParameters: Object.keys(KEY_PARAMETER_MAP).length,
      };
    },

    setDebugExpression(kind, index, weight = 1) {
      state.functionExpressionUntil = 0;
      state.functionExpressionRestoreKind = 'auto';
      state.functionExpressionRestoreIndex = -1;
      state.functionExpressionRestoreStartedAt = 0;
      const normalized = String(kind || 'auto').toLowerCase();
      const parsedIndex = Math.max(-1, Number(index) | 0);
      if ((normalized === 'live2d' || normalized === 'face') && parsedIndex >= 0) {
        state.debugExpressionKind = normalized;
        state.debugExpressionIndex = parsedIndex;
        state.debugExpressionStartedAt = performance.now();
        state.debugExpressionWeight = Math.max(0, Math.min(1, Number(weight) || 0));
      } else {
        state.debugExpressionKind = 'auto';
        state.debugExpressionIndex = -1;
        state.debugExpressionStartedAt = 0;
        state.debugExpressionWeight = 1;
      }
      if (IS_MVER) syncMverFaceAndExpressions();
    },

    setGlobalReverse(enabled) {
      state.globalReverse = Boolean(enabled);
      syncGlobalReverse();
    },

    setHideWatermark(hidden) {
      applyLive2DWatermarkVisibility(hidden);
    },

    setMotionTracking(
      bodyX, bodyY, crouch, leftArmUp, rightArmUp,
      leftHandX, leftHandY, rightHandX, rightHandY,
      leftLegMotion, rightLegMotion, confidence,
    ) {
      applyLive2DMotionTracking(
        bodyX, bodyY, crouch, leftArmUp, rightArmUp,
        leftHandX, leftHandY, rightHandX, rightHandY,
        leftLegMotion, rightLegMotion, confidence,
      );
    },

    clearMotionTracking() {
      clearLive2DMotionTracking();
    },

    setDisplayScale(scale) {
      if (!AXON_LIVE2D_DISPLAY) return;
      AXON_LIVE2D_DISPLAY_SCALE = Math.max(0.5, Math.min(2.0, Number(scale) || 1));
      if (renderer) renderer.resize();
    },

    setDisplayOffset(x, y) {
      if (!AXON_LIVE2D_DISPLAY) return;
      AXON_LIVE2D_DISPLAY_OFFSET_X = Math.max(-0.9, Math.min(0.9, Number(x) || 0));
      AXON_LIVE2D_DISPLAY_OFFSET_Y = Math.max(-0.9, Math.min(0.9, Number(y) || 0));
      if (renderer) renderer.resize();
    },

    setRenderQuality(mode) {
      const next = String(mode || '').toLowerCase() === 'clear' ? 'clear' : 'normal';
      const nextCap = next === 'clear' ? RENDER_DPR_CAP_CLEAR : RENDER_DPR_CAP_NORMAL;
      if (renderQuality === next && Math.abs(renderDprCap - nextCap) < 0.001) return;
      renderQuality = next;
      renderDprCap = nextCap;
      if (renderer) renderer.resize();
    },

    triggerPhysicsGroupAction(groupKey, magnitude = 1) {
      return renderer && typeof renderer.triggerPhysicsGroupAction === 'function'
        ? renderer.triggerPhysicsGroupAction(String(groupKey || ''), magnitude)
        : false;
    },

    setPhysicsControls(value) {
      const next = normalizePhysicsControls(value);
      const resetParticles = physicsEnabledTopologyChanged(physicsControls, next);
      physicsControls = next;
      if (renderer && typeof renderer.invalidatePhysicsControlState === 'function') {
        // Strength drags keep particle continuity; only enable/disable topology changes rebuild
        // particle state to prevent a long-disabled group from jumping when it is turned back on.
        renderer.invalidatePhysicsControlState(resetParticles);
      }
      return true;
    },

    clear() {
      state.leftKey = null;
      state.rightKey = null;
      state.mverPressed.clear();
      state.modernPressed.clear();
      state.modernGamepadPressed.clear();
      state.modernParameterCounts.clear();
      nativePolledKeys.clear();
      lastNativeKeySnapshot = '';
      nextNativeKeyReconcileAt = 0;
      state.mverPressOrder = [];
      state.mverLx = state.mverLy = state.mverRx = state.mverRy = 0;
      state.mverL3 = state.mverR3 = false;
      state.mverLatchedFaceIndex = -1;
      state.mverExpressionIndex = -1;
      state.mverExpressionStartedAt = 0;
      state.debugExpressionKind = 'auto';
      state.debugExpressionIndex = -1;
      state.debugExpressionStartedAt = 0;
      state.debugExpressionWeight = 1;
      state.mverBindingStates.clear();
      state.mverBindingOrder.clear();
      state.mverBindingSequence = 0;
      state.mverComboStates.clear();
      state.mverMotionIndex = -1;
      state.mverMotionStartedAt = 0;
      state.mverMotionLockHand = false;
      state.parameterPulseUntil.clear();
      state.parameterPreviewRestoreToggle.clear();
      state.parameterSequenceEvents = [];
      state.parameterSequenceGeneration += 1;
      state.functionExpressionUntil = 0;
      state.functionExpressionRestoreKind = 'auto';
      state.functionExpressionRestoreIndex = -1;
      state.functionExpressionRestoreStartedAt = 0;
      stopAllMverSounds();
      state.mouseButtons = 0;
      state.mouseVisualButtons = 0;
      state.mousePressUntil[0] = 0;
      state.mousePressUntil[1] = 0;
      state.cursorX = state.targetX = 0.5;
      state.cursorY = state.targetY = 0.5;
      state.pointerActive = false;
      setOverlay('left', null);
      setOverlay('right', null);
      if (IS_MVER) {
        for (const [id, image] of mverLayers) {
          if (id.startsWith('mver-key-') || id === 'mver-face') image.classList.add('hidden');
        }
        syncMverKeys();
        syncMverHands();
        syncMverFaceAndExpressions();
        syncMverMouseVisual();
      }
      if (renderer) {
        renderer.clearOverrides();
        if (IS_MVER) renderer.resetDrag();
        applySavedParameterValues();
        syncHandOverrides();
      }
    },

    debugState() {
      return {
        inputReady: true,
        rendererReady: Boolean(renderer && state.ready),
        ready: state.ready,
        mver: IS_MVER,
        useLive2d: MVER_USE_LIVE2D,
        runtimeError: state.runtimeError,
        staticFallback: state.staticFallback,
        inputCount: state.inputCount,
        lastInput: state.lastInput,
        pressed: Array.from(state.mverPressed),
        keyBindings: MVER_KEY_BINDINGS.length,
        handBindings: MVER_HAND_BINDINGS.length,
        spriteHandsAuthoritative: MVER_SPRITE_HANDS_AUTHORITATIVE,
        faceBindings: MVER_FACE_BINDINGS.length,
        expressionBindings: MVER_EXPRESSION_BINDINGS.length,
        debugExpression: [state.debugExpressionKind, state.debugExpressionIndex],
        motionBindings: MVER_MOTION_BINDINGS.length + MVER_MOTION_LOCK_HAND_BINDINGS.length,
        baseLayerRole: MVER_BASE_LAYER_ROLE,
        baseLayerZ: MVER_BASE_LAYER_Z,
        fullFrameOffset: [MVER_FULLFRAME_OFFSET_X, MVER_FULLFRAME_OFFSET_Y],
        parameterCount: renderer ? renderer.parameters.count : 0,
        discoveredParameters: PARAMETER_DEFINITIONS.length,
        discoveredKeySemantics: Object.keys(KEY_PARAMETER_MAP).length,
        physicsSettings: MVER_PHYSICS && Array.isArray(MVER_PHYSICS.PhysicsSettings) ? MVER_PHYSICS.PhysicsSettings.length : 0,
        sequenceQueue: state.parameterSequenceEvents.length,
        webgl: renderer ? renderer.glInfo : '',
        maxTextureSize: renderer ? renderer.maxTextureSize : 0,
        drawableColorStats: renderer ? renderer.drawableColorStats : null,
        rendererCompatibility: renderer ? {
          premultipliedAlpha: true,
          alphaBlendSeparate: true,
          softMaskFramebuffer: Boolean(renderer.maskFramebuffer && renderer.maskFramebufferUsable),
          maskFramebufferUsable: renderer.maskFramebufferUsable,
          maskSize: [renderer.maskWidth || 0, renderer.maskHeight || 0],
          textureDiagnostics: renderer.textureDiagnostics,
        } : null,
        renderQuality,
        renderDprCap,
        renderDprCapNormal: RENDER_DPR_CAP_NORMAL,
        renderDprCapClear: RENDER_DPR_CAP_CLEAR,
        textureEstimatedRgbaBytes: TEXTURE_ESTIMATED_RGBA_BYTES,
        physicsActiveFps: PHYSICS_ACTIVE_FPS,
        physicsIdleFps: PHYSICS_IDLE_FPS,
        physicsGlobalStrength: physicsControls.globalStrength,
        physicsGroupControls: physicsControls.groups,
        physicsGroupKeys: PHYSICS_GROUP_KEYS,
        paused: runtimePaused,
      };
    },

    pause() {
      pauseRuntime();
    },

    resume() {
      resumeRuntime();
    },

    dispose() {
      disposeRuntime();
    },

    isReady() {
      return state.ready;
    },
  };

  console.info('[AxonBongoCat] input bridge installed', {
    mver: IS_MVER,
    live2d: MVER_USE_LIVE2D,
    keyBindings: MVER_KEY_BINDINGS.length,
    handBindings: MVER_HAND_BINDINGS.length,
    spriteHandsAuthoritative: MVER_SPRITE_HANDS_AUTHORITATIVE,
    faceBindings: MVER_FACE_BINDINGS.length,
    pose: Boolean(LIVE2D_POSE),
    eyeBlinkIds: LIVE2D_EYE_BLINK_IDS.length,
  });
  let nextNativeKeyReconcileAt = 0;
  let lastNativeKeySnapshot = '';

  function reconcileNativeHeldKeys(now) {
    if (!IS_MVER || now < nextNativeKeyReconcileAt || !window.AxonNativeInput
      || typeof window.AxonNativeInput.snapshotKeys !== 'function') return;
    nextNativeKeyReconcileAt = now + NATIVE_KEY_RECONCILE_INTERVAL_MS;
    let raw;
    try { raw = String(window.AxonNativeInput.snapshotKeys() || '{}'); }
    catch (_) { return; }
    if (raw === lastNativeKeySnapshot) return;
    lastNativeKeySnapshot = raw;

    let snapshot;
    try { snapshot = JSON.parse(raw); } catch (_) { return; }
    const keys = new Set(Array.isArray(snapshot.keys) ? snapshot.keys.map(String) : []);
    for (const key of nativePolledKeys) {
      if (!keys.has(key)) setMverKey(key, false, false);
    }
    for (const key of keys) {
      if (!nativePolledKeys.has(key)) setMverKey(key, true, false);
    }
    nativePolledKeys.clear();
    for (const key of keys) nativePolledKeys.add(key);
  }

  if (IS_MVER && !MVER_KEY_BINDINGS.length && !MVER_HAND_BINDINGS.length) {
    console.warn('[AxonBongoCat] Mver package has no standard keyboard/hand bindings; re-import the source ZIP');
  }

  function updatePointer(deltaMs) {
    if (!state.pointerActive || (!renderer && !IS_MVER)) return false;
    const dx = state.targetX - state.cursorX;
    const dy = state.targetY - state.cursorY;
    // Once the damped pointer has settled, its renderer inputs and Mver arm geometry are already
    // persistent. Do not redraw the arm / rewrite parameter maps forever while the mouse is idle.
    if (dx * dx + dy * dy < 1e-8) {
      if (state.cursorX === state.targetX && state.cursorY === state.targetY) return false;
      state.cursorX = state.targetX;
      state.cursorY = state.targetY;
    } else {
      const alpha = 1 - DAMPING_DECAY ** (deltaMs / (1000 / 60));
      state.cursorX += dx * alpha;
      state.cursorY += dy * alpha;
    }
    if (renderer) applyPointerOverrides(state.cursorX, state.cursorY);
    if (IS_MVER) syncMverMouseVisual();
    return true;
  }

  function tick(now) {
    animationHandle = 0;
    if (runtimeDisposed || runtimePaused) return;
    // A modern imported model that fell back to its static cover has no live work left to do.
    // Stop the RAF loop entirely instead of waking WebView 60/120 times per second forever.
    if (state.staticFallback && !IS_MVER && !renderer) return;
    scheduleAnimation();
    if (!renderer && !IS_MVER) return;
    if (lastFrameTime && now - lastFrameTime < FRAME_INTERVAL - 0.5) return;

    const deltaMs = Math.min(100, Math.max(0.1, lastFrameTime ? now - lastFrameTime : FRAME_INTERVAL));
    lastFrameTime = now;
    reconcileNativeHeldKeys(now);
    const mouseVisualChanged = updateMouseButtonVisual(now);
    const pointerChanged = updatePointer(deltaMs);
    updateMverMotionLifetime(now);
    if (IS_MVER && mouseVisualChanged && !pointerChanged) syncMverMouseVisual();
    processParameterSequences(now);
    if (renderer && state.parameterPulseUntil.size) {
      for (const [id, until] of state.parameterPulseUntil) {
        if (now < until) continue;
        state.parameterPulseUntil.delete(id);
        if (state.parameterPreviewRestoreToggle.has(id)) {
          const wasToggled = Boolean(state.parameterPreviewRestoreToggle.get(id));
          state.parameterPreviewRestoreToggle.delete(id);
          if (wasToggled) {
            renderer.setNormalizedOverride(id, 1);
            state.parameterToggles.set(id, true);
          } else {
            restoreSavedParameter(id);
          }
        } else {
          restoreSavedParameter(id);
        }
      }
    }
    if (state.functionExpressionUntil > 0 && now >= state.functionExpressionUntil) {
      state.functionExpressionUntil = 0;
      state.debugExpressionKind = state.functionExpressionRestoreKind;
      state.debugExpressionIndex = state.functionExpressionRestoreIndex;
      state.debugExpressionStartedAt = state.functionExpressionRestoreStartedAt;
      state.functionExpressionRestoreKind = 'auto';
      state.functionExpressionRestoreIndex = -1;
      state.functionExpressionRestoreStartedAt = 0;
      if (IS_MVER) syncMverFaceAndExpressions();
    }
    if (renderer) {
      renderer.setEyeBlink(eyeBlinkValue(now));
      renderer.render(deltaMs / 1000);
    }
  }

  function compileShader(gl, type, source) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      throw new Error(gl.getShaderInfoLog(shader) || 'shader compile failed');
    }
    return shader;
  }

  function cubicBezierScalar(a, b, c, d, t) {
    const u = 1 - t;
    return u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d;
  }

  function compileMotionCurve(curve) {
    if (!curve || !Array.isArray(curve.Segments)) return [];
    if (Array.isArray(curve.__axonSegments)) return curve.__axonSegments;
    const raw = curve.Segments;
    if (raw.length < 2) return [];
    const result = [];
    let time = Number(raw[0]) || 0;
    let value = Number(raw[1]) || 0;
    let cursor = 2;
    while (cursor < raw.length) {
      const type = Number(raw[cursor++]) | 0;
      if (type === 1 && cursor + 5 < raw.length) {
        const segment = {
          type, t0: time, v0: value,
          c1t: Number(raw[cursor++]) || 0, c1v: Number(raw[cursor++]) || 0,
          c2t: Number(raw[cursor++]) || 0, c2v: Number(raw[cursor++]) || 0,
          t1: Number(raw[cursor++]) || 0, v1: Number(raw[cursor++]) || 0,
        };
        result.push(segment);
        time = segment.t1; value = segment.v1;
      } else if ((type === 0 || type === 2 || type === 3) && cursor + 1 < raw.length) {
        const segment = {
          type, t0: time, v0: value,
          t1: Number(raw[cursor++]) || 0, v1: Number(raw[cursor++]) || 0,
        };
        result.push(segment);
        time = segment.t1; value = segment.v1;
      } else {
        break;
      }
    }
    try { Object.defineProperty(curve, '__axonSegments', { value: result, configurable: true }); } catch (_) {}
    return result;
  }

  function evaluateMotionCurve(curve, time) {
    const segments = compileMotionCurve(curve);
    if (!segments.length) return 0;
    if (time <= segments[0].t0) return segments[0].v0;
    for (const segment of segments) {
      if (time > segment.t1) continue;
      const span = Math.max(0.000001, segment.t1 - segment.t0);
      const ratio = Math.max(0, Math.min(1, (time - segment.t0) / span));
      if (segment.type === 0) return segment.v0 + (segment.v1 - segment.v0) * ratio;
      if (segment.type === 2) return ratio >= 1 ? segment.v1 : segment.v0;
      if (segment.type === 3) return ratio > 0 ? segment.v1 : segment.v0;
      if (segment.type === 1) {
        // Segment time is also a cubic curve. Solve u with a small monotonic binary search.
        let lo = 0, hi = 1, u = ratio;
        for (let i = 0; i < 10; i++) {
          u = (lo + hi) * 0.5;
          const x = cubicBezierScalar(segment.t0, segment.c1t, segment.c2t, segment.t1, u);
          if (x < time) lo = u; else hi = u;
        }
        return cubicBezierScalar(segment.v0, segment.c1v, segment.c2v, segment.v1, u);
      }
    }
    return segments[segments.length - 1].v1;
  }

  function motionFadeWeight(curve, meta, localTime, duration) {
    let weight = 1;
    const curveFadeIn = Number(curve && curve.FadeInTime);
    const curveFadeOut = Number(curve && curve.FadeOutTime);
    const metaFadeIn = Number(meta && meta.FadeInTime);
    const metaFadeOut = Number(meta && meta.FadeOutTime);
    const fadeIn = Number.isFinite(curveFadeIn) && curveFadeIn >= 0 ? curveFadeIn : (Number.isFinite(metaFadeIn) ? metaFadeIn : 0);
    const fadeOut = Number.isFinite(curveFadeOut) && curveFadeOut >= 0 ? curveFadeOut : (Number.isFinite(metaFadeOut) ? metaFadeOut : 0);
    if (fadeIn > 0) {
      const t = Math.max(0, Math.min(1, localTime / fadeIn));
      const s = Math.sin(t * Math.PI * 0.5);
      weight *= s * s;
    }
    if (fadeOut > 0 && duration > 0) {
      const t = Math.max(0, Math.min(1, (duration - localTime) / fadeOut));
      const s = Math.sin(t * Math.PI * 0.5);
      weight *= s * s;
    }
    return weight;
  }

  function normalizePhysicsValue(value, parameterMin, parameterMax, parameterDefault, normalized) {
    const min = Math.min(Number(parameterMin) || 0, Number(parameterMax) || 0);
    const max = Math.max(Number(parameterMin) || 0, Number(parameterMax) || 0);
    const normalizedMin = Math.min(
      Number.isFinite(Number(normalized && normalized.Minimum)) ? Number(normalized.Minimum) : -1,
      Number.isFinite(Number(normalized && normalized.Maximum)) ? Number(normalized.Maximum) : 1,
    );
    const normalizedMax = Math.max(
      Number.isFinite(Number(normalized && normalized.Minimum)) ? Number(normalized.Minimum) : -1,
      Number.isFinite(Number(normalized && normalized.Maximum)) ? Number(normalized.Maximum) : 1,
    );
    const normalizedDefault = Number.isFinite(Number(normalized && normalized.Default))
      ? Number(normalized.Default) : 0;
    const middle = min + (max - min) * 0.5;
    const clamped = Math.max(min, Math.min(max, Number(value) || 0));
    const delta = clamped - middle;
    if (delta > 0) {
      const span = Math.max(0.000001, max - middle);
      return normalizedDefault + (normalizedMax - normalizedDefault) * (delta / span);
    }
    if (delta < 0) {
      const span = Math.min(-0.000001, min - middle);
      return normalizedDefault + (normalizedMin - normalizedDefault) * (delta / span);
    }
    return normalizedDefault;
  }

  function rotateVector(x, y, radians) {
    const c = Math.cos(radians);
    const s = Math.sin(radians);
    return [x * c - y * s, x * s + y * c];
  }

  class CoreRenderer {
    constructor(core, model, images) {
      this.core = core;
      this.model = model;
      this.drawables = model.drawables;
      this.parameters = model.parameters;
      this.canvasInfo = model.canvasinfo;
      this.drawableColorStats = this.measureDrawableColorUsage();
      // Renderer compatibility state is shared by standalone Live2D and imported keyboard-cat
      // models. Keep diagnostics in the renderer so model-specific white/black failures can be
      // separated from WebView/window failures without adding user-facing debug toggles.
      this.missingTextureDrawables = new Set();
      this.maskFramebuffer = null;
      this.maskTexture = null;
      this.maskWidth = 0;
      this.maskHeight = 0;
      this.maskFramebufferUsable = true;
      this.lastMaskKey = '';
      this.textureDiagnostics = { uploaded: 0, mipmapped: 0, missingDrawableTextures: 0 };
      this.paramIndex = new Map();
      this.paramRanges = new Map();
      this.overrides = new Map();
      this.locks = new Map();
      this.frameInputs = new Map();
      this.motionTouchedParameters = new Set();
      // Camera results arrive at ~15-25 Hz. Keep targets separate from rendered values so the
      // 60 Hz Cubism loop interpolates them instead of visibly stepping every camera frame.
      this.trackingTargets = new Map();
      this.trackingValues = new Map();
      this.trackingFinal = new Set();
      // A parameter may be driven by multiple capture systems (for example ParamMouthOpenY is
      // present in both detailed face tracking and full-body tracking). Keep explicit ownership
      // so a lower-priority asynchronous source cannot overwrite or clear a higher-priority one.
      this.trackingOwners = new Map();
      this.trackingPriorities = new Map();
      this.defaults = Array.from(this.parameters.defaultValues);
      // CubismModel::SaveParameters/LoadParameters semantics: retain the authored base state
      // between frames, then apply transient input/blink/expression/breath/physics on top.
      this.savedParameters = Array.from(this.parameters.values);
      this.eyeBlink = 1;
      this.modelOpacity = 1;
      this.elapsedSeconds = 0;
      this.idleMotionIndex = -1;
      this.idleMotionStartedAt = 0;
      this.lastIdleMotionIndex = -1;
      // CubismTargetPoint state. Mver uses this acceleration/deceleration curve instead
      // of writing ParamMouseX/Y straight into the model.
      this.dragTargetX = 0;
      this.dragTargetY = 0;
      this.dragX = 0;
      this.dragY = 0;
      this.dragVX = 0;
      this.dragVY = 0;
      this.dragLastTime = 0;
      this.dragUserTime = 0;

      for (let i = 0; i < this.parameters.count; i++) {
        const id = String(this.parameters.ids[i]);
        this.paramIndex.set(id, i);
        // Motion capture queries ranges many times per update. Cache the pair once instead of
        // allocating a fresh [min,max] array for every alias on every frame.
        this.paramRanges.set(id, [this.parameters.minimumValues[i], this.parameters.maximumValues[i]]);
      }
      for (const [id, normalized] of Object.entries(PARAMETER_LOCKS)) {
        const index = this.paramIndex.get(String(id));
        if (index === undefined) continue;
        const ratio = Math.max(0, Math.min(1, Number(normalized) || 0));
        const min = this.parameters.minimumValues[index];
        const max = this.parameters.maximumValues[index];
        this.locks.set(index, min + (max - min) * ratio);
      }
      this.partIndex = new Map();
      if (model.parts && model.parts.ids) {
        for (let i = 0; i < model.parts.count; i++) this.partIndex.set(String(model.parts.ids[i]), i);
      }
      this.physicsStates = this.createPhysicsStates();
      this.physicsOutputIndices = this.createPhysicsOutputIndices();
      this.physicsOutputDeltas = new Map();
      this.physicsAccumulator = 0;
      // Short user-triggered impulses keep Physics on the active cadence while the authored
      // particle chain settles. This is an action, not an enable/disable toggle.
      this.physicsActionUntil = 0;
      this.physicsActionSequence = 0;
      this.poseState = this.createPoseState();

      const contextOptions = {
        alpha: true,
        antialias: true,
        stencil: true,
        premultipliedAlpha: true,
        preserveDrawingBuffer: false,
      };
      const gl = canvas.getContext('webgl', contextOptions)
        || canvas.getContext('experimental-webgl', contextOptions);
      if (!gl) throw new Error('WebGL unavailable');
      this.gl = gl;
      this.maxTextureSize = Math.max(1, Number(gl.getParameter(gl.MAX_TEXTURE_SIZE)) || 4096);
      const debugInfo = gl.getExtension('WEBGL_debug_renderer_info');
      this.glInfo = debugInfo
        ? `${gl.getParameter(debugInfo.UNMASKED_VENDOR_WEBGL)} / ${gl.getParameter(debugInfo.UNMASKED_RENDERER_WEBGL)}`
        : String(gl.getParameter(gl.RENDERER) || 'WebGL');
      console.info('[AxonBongoCat] WebGL ready:', this.glInfo);
      this.program = this.createProgram(false);
      this.maskProgram = this.createProgram(true);
      this.textures = images.map((image) => this.createTexture(image));
      this.positionBuffer = gl.createBuffer();
      this.uvBuffer = gl.createBuffer();
      this.indexBuffer = gl.createBuffer();
      this.boundResize = () => this.resize();
      this.resize();
      addEventListener('resize', this.boundResize, { passive: true });
    }

    measureDrawableColorUsage() {
      const d = this.drawables;
      let multiply = 0;
      let screen = 0;
      let authored = 0;
      for (let i = 0; i < d.count; i++) {
        const base = i * 4;
        const m = d.multiplyColors;
        const sc = d.screenColors;
        const hasMultiply = Boolean(m) && (
          Math.abs((Number(m[base]) || 0) - 1) > 0.00001
          || Math.abs((Number(m[base + 1]) || 0) - 1) > 0.00001
          || Math.abs((Number(m[base + 2]) || 0) - 1) > 0.00001
        );
        const hasScreen = Boolean(sc) && (
          Math.abs(Number(sc[base]) || 0) > 0.00001
          || Math.abs(Number(sc[base + 1]) || 0) > 0.00001
          || Math.abs(Number(sc[base + 2]) || 0) > 0.00001
        );
        if (hasMultiply) multiply++;
        if (hasScreen) screen++;
        if (hasMultiply || hasScreen) authored++;
      }
      let masked = 0;
      let additive = 0;
      let multiplicative = 0;
      const utils = this.core && this.core.Utils ? this.core.Utils : {};
      for (let i = 0; i < d.count; i++) {
        if (Number(d.maskCounts && d.maskCounts[i]) > 0) masked++;
        const flags = d.constantFlags ? d.constantFlags[i] : 0;
        if (typeof utils.hasBlendAdditiveBit === 'function' && utils.hasBlendAdditiveBit(flags)) additive++;
        if (typeof utils.hasBlendMultiplicativeBit === 'function' && utils.hasBlendMultiplicativeBit(flags)) multiplicative++;
      }
      const stats = { drawableCount: d.count, authored, multiply, screen, masked, additive, multiplicative };
      console.info('[AxonBongoCat] drawable color pipeline', JSON.stringify(stats));
      return stats;
    }

    readDrawableColor(colors, drawableIndex, identity) {
      const fallback = identity || [1, 1, 1, 1];
      const base = drawableIndex * 4;
      const clamp = (value, defaultValue) => {
        const number = Number(value);
        return Number.isFinite(number) ? Math.max(0, Math.min(1, number)) : defaultValue;
      };
      // Cubism Core 4.2/5 exposes a flat Float32Array. A few wrapped Core builds expose vectors;
      // accept both so an unexpected wrapper cannot turn the default multiply color into black.
      if (colors && colors.length >= base + 4 && Number.isFinite(Number(colors[base]))) {
        return [
          clamp(colors[base], fallback[0]),
          clamp(colors[base + 1], fallback[1]),
          clamp(colors[base + 2], fallback[2]),
          clamp(colors[base + 3], fallback[3]),
        ];
      }
      const item = colors && colors[drawableIndex];
      if (item && typeof item === 'object') {
        return [
          clamp(item.R ?? item.r ?? item.X ?? item.x ?? item[0], fallback[0]),
          clamp(item.G ?? item.g ?? item.Y ?? item.y ?? item[1], fallback[1]),
          clamp(item.B ?? item.b ?? item.Z ?? item.z ?? item[2], fallback[2]),
          clamp(item.A ?? item.a ?? item.W ?? item.w ?? item[3], fallback[3]),
        ];
      }
      return fallback.slice();
    }

    drawableOpacity(drawableIndex) {
      const value = Number(this.drawables.opacities && this.drawables.opacities[drawableIndex]);
      return Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : 1;
    }

    drawableTexture(drawableIndex) {
      const index = Number(this.drawables.textureIndices && this.drawables.textureIndices[drawableIndex]);
      if (!Number.isInteger(index) || index < 0 || index >= this.textures.length || !this.textures[index]) {
        if (!this.missingTextureDrawables.has(drawableIndex)) {
          this.missingTextureDrawables.add(drawableIndex);
          this.textureDiagnostics.missingDrawableTextures = this.missingTextureDrawables.size;
          console.error('[AxonBongoCat] drawable references missing texture', JSON.stringify({ drawableIndex, textureIndex: index, uploaded: this.textures.length }));
        }
        return null;
      }
      return this.textures[index];
    }

    async loadTexturesSequentially(urls) {
      this.textures.length = 0;
      let uploadedBytes = 0;
      for (let i = 0; i < urls.length; i++) {
        if (runtimeDisposed) throw new Error('runtime disposed during texture load');
        const image = await loadImage(urls[i]);
        const width = Math.max(0, Number(image.naturalWidth || image.width) || 0);
        const height = Math.max(0, Number(image.naturalHeight || image.height) || 0);
        this.textures.push(this.createTexture(image));
        uploadedBytes += width * height * 4;
        // Release decoded DOM pixels immediately. Large community models otherwise hold the decoded
        // bitmap and WebGL copy at the same time until GC, doubling peak memory during startup.
        try { image.onload = image.onerror = null; image.src = ''; } catch (_) {}
        if ((i + 1) % TEXTURE_UPLOAD_YIELD_EVERY === 0) {
          try { this.gl.flush(); } catch (_) {}
          await new Promise((resolve) => setTimeout(resolve, 0));
        }
      }
      console.info('[AxonBongoCat] texture upload complete', JSON.stringify({ count: urls.length, uploadedBytes, estimatedBytes: TEXTURE_ESTIMATED_RGBA_BYTES }));
    }

    createProgram(mask) {
      const gl = this.gl;
      const vertex = `
        attribute vec2 aPosition;
        attribute vec2 aUv;
        varying vec2 vUv;
        varying vec2 vScreenUv;
        uniform vec4 uCanvas;
        uniform vec2 uViewport;
        void main() {
          float px = uCanvas.x + aPosition.x * uCanvas.z;
          float py = uCanvas.y - aPosition.y * uCanvas.z;
          vec4 position = vec4(px / uCanvas.w * 2.0 - 1.0, 1.0 - py / uViewport.y * 2.0, 0.0, 1.0);
          gl_Position = position;
          vUv = aUv;
          vScreenUv = position.xy * 0.5 + 0.5;
        }
      `;
      const fragment = mask ? `
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTexture;
        void main() {
          // Cubism clipping masks are alpha masks. Do not apply Multiply/Screen color here:
          // current Cubism Web Framework intentionally keeps drawable colors out of mask setup.
          float a = texture2D(uTexture, vUv).a;
          if (a < 0.001) discard;
          gl_FragColor = vec4(a, a, a, a);
        }
      ` : `
        precision mediump float;
        varying vec2 vUv;
        varying vec2 vScreenUv;
        uniform sampler2D uTexture;
        uniform sampler2D uMaskTexture;
        uniform float uOpacity;
        uniform float uUseMask;
        uniform float uMaskInverted;
        uniform vec4 uMultiplyColor;
        uniform vec4 uScreenColor;
        void main() {
          // This matches Cubism Web Framework's premultiplied-alpha color pipeline. MultiplyColor
          // defaults to white and ScreenColor defaults to black. Sanitized CPU-side fallbacks stop
          // absent/invalid Core color buffers from turning an otherwise valid model solid black.
          vec4 texColor = texture2D(uTexture, vUv);
          texColor.rgb = texColor.rgb * uMultiplyColor.rgb;
          texColor.rgb = (texColor.rgb + uScreenColor.rgb * texColor.a)
            - (texColor.rgb * uScreenColor.rgb);
          vec4 color = texColor * uOpacity;
          if (uUseMask > 0.5) {
            float maskValue = texture2D(uMaskTexture, vScreenUv).a;
            if (uMaskInverted > 0.5) maskValue = 1.0 - maskValue;
            color *= clamp(maskValue, 0.0, 1.0);
          }
          gl_FragColor = color;
        }
      `;

      const program = gl.createProgram();
      gl.attachShader(program, compileShader(gl, gl.VERTEX_SHADER, vertex));
      gl.attachShader(program, compileShader(gl, gl.FRAGMENT_SHADER, fragment));
      gl.linkProgram(program);
      if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
        throw new Error(gl.getProgramInfoLog(program) || 'program link failed');
      }
      return program;
    }

    createTexture(image) {
      const gl = this.gl;
      const width = Math.max(0, Number(image && (image.naturalWidth || image.width)) || 0);
      const height = Math.max(0, Number(image && (image.naturalHeight || image.height)) || 0);
      if (width > this.maxTextureSize || height > this.maxTextureSize) {
        throw new Error(`texture ${width}x${height} exceeds WebGL limit ${this.maxTextureSize}`);
      }
      const texture = gl.createTexture();
      if (!texture) throw new Error('WebGL texture allocation failed');
      gl.bindTexture(gl.TEXTURE_2D, texture);
      gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
      // Cubism Web's renderer is premultiplied-alpha only. Always perform the premultiply exactly
      // once at WebGL upload; Java-side atlas adaptation now preserves straight-alpha source data.
      gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      const powerOfTwo = width > 0 && height > 0 && (width & (width - 1)) === 0 && (height & (height - 1)) === 0;
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, powerOfTwo ? gl.LINEAR_MIPMAP_LINEAR : gl.LINEAR);
      // Clear any stale error before upload, then fail loudly instead of publishing an incomplete
      // renderer that Java later mistakes for a valid custom style.
      gl.getError();
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, image);
      let uploadError = gl.getError();
      if (uploadError === gl.NO_ERROR && powerOfTwo) {
        gl.generateMipmap(gl.TEXTURE_2D);
        uploadError = gl.getError();
        if (uploadError === gl.NO_ERROR) this.textureDiagnostics.mipmapped++;
      }
      if (uploadError !== gl.NO_ERROR) {
        gl.deleteTexture(texture);
        throw new Error(`WebGL texture upload failed: 0x${uploadError.toString(16)} (${width}x${height})`);
      }
      this.textureDiagnostics.uploaded++;
      gl.bindTexture(gl.TEXTURE_2D, null);
      return texture;
    }

    releaseMaskTarget() {
      const gl = this.gl;
      try { if (this.maskFramebuffer) gl.deleteFramebuffer(this.maskFramebuffer); } catch (_) {}
      try { if (this.maskTexture) gl.deleteTexture(this.maskTexture); } catch (_) {}
      this.maskFramebuffer = null;
      this.maskTexture = null;
      this.maskWidth = 0;
      this.maskHeight = 0;
      this.lastMaskKey = '';
    }

    ensureMaskTarget() {
      if (!this.maskFramebufferUsable) return false;
      const gl = this.gl;
      // A dedicated full-screen clipping target avoids the old binary stencil approximation while
      // keeping GPU memory bounded on 2K/3K phones. NDC coordinates make the lower-resolution mask
      // texture line up exactly with the main canvas.
      const maxEdge = renderQuality === 'clear' ? 1536 : 1024;
      const scale = Math.min(1, maxEdge / Math.max(1, this.pixelWidth, this.pixelHeight));
      const width = Math.max(64, Math.round(this.pixelWidth * scale));
      const height = Math.max(64, Math.round(this.pixelHeight * scale));
      if (this.maskFramebuffer && this.maskTexture && this.maskWidth === width && this.maskHeight === height) return true;
      this.releaseMaskTarget();
      const texture = gl.createTexture();
      const framebuffer = gl.createFramebuffer();
      if (!texture || !framebuffer) {
        this.maskFramebufferUsable = false;
        this.releaseMaskTarget();
        return false;
      }
      gl.bindTexture(gl.TEXTURE_2D, texture);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, width, height, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
      gl.bindFramebuffer(gl.FRAMEBUFFER, framebuffer);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0);
      const complete = gl.checkFramebufferStatus(gl.FRAMEBUFFER) === gl.FRAMEBUFFER_COMPLETE;
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      gl.bindTexture(gl.TEXTURE_2D, null);
      if (!complete) {
        try { gl.deleteFramebuffer(framebuffer); } catch (_) {}
        try { gl.deleteTexture(texture); } catch (_) {}
        this.maskFramebufferUsable = false;
        console.warn('[AxonBongoCat] alpha clipping framebuffer unavailable; using stencil fallback');
        return false;
      }
      this.maskFramebuffer = framebuffer;
      this.maskTexture = texture;
      this.maskWidth = width;
      this.maskHeight = height;
      return true;
    }

    buildAlphaMask(maskIndices, maskCount) {
      if (!this.ensureMaskTarget()) return false;
      const key = Array.from(maskIndices || []).slice(0, maskCount).join(',');
      if (key && key === this.lastMaskKey) return true;
      const gl = this.gl;
      gl.bindFramebuffer(gl.FRAMEBUFFER, this.maskFramebuffer);
      gl.viewport(0, 0, this.maskWidth, this.maskHeight);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.disable(gl.DEPTH_TEST);
      gl.disable(gl.STENCIL_TEST);
      gl.disable(gl.CULL_FACE);
      gl.enable(gl.BLEND);
      // Union mask alpha while preserving soft/feathered edges.
      gl.blendEquation(gl.FUNC_ADD);
      gl.blendFuncSeparate(gl.ONE, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      for (let i = 0; i < maskCount; i++) this.draw(maskIndices[i], true, false, false);
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      gl.viewport(0, 0, this.pixelWidth, this.pixelHeight);
      this.lastMaskKey = key;
      return true;
    }

    resize() {
      // High-DPI Android devices can allocate very large color/stencil/mask buffers even when the
      // overlay is visually small. Cap render DPR according to the import-time texture pressure;
      // model texture resolution itself is untouched.
      const dpr = Math.max(1, Math.min(renderDprCap, devicePixelRatio || 1));
      const cssWidth = Math.max(1, canvas.clientWidth || innerWidth || 1);
      const cssHeight = Math.max(1, canvas.clientHeight || innerHeight || 1);
      const width = Math.max(1, Math.round(cssWidth * dpr));
      const height = Math.max(1, Math.round(cssHeight * dpr));
      if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width;
        canvas.height = height;
      }
      this.pixelWidth = width;
      this.pixelHeight = height;

      // Imported packs can use a canvas radically different from the built-in 612x354 model
      // (for example 1400x1400 standard-mode skins). Fit the actual Cubism canvas with
      // contain semantics instead of scaling it against the built-in keyboard dimensions.
      const nativeWidth = Math.max(1, Number(this.canvasInfo.CanvasWidth) || DESIGN_WIDTH);
      const nativeHeight = Math.max(1, Number(this.canvasInfo.CanvasHeight) || DESIGN_HEIGHT);
      const containScale = Math.min(width / nativeWidth, height / nativeHeight);
      // l2d_correct > 1 is a deliberate desktop zoom in Mver, but an Android overlay has a
      // hard clipping rectangle. Never let that zoom crop ears/hair/feet; values below 1 are
      // still respected because they intentionally shrink the model.
      const safeCorrect = AXON_LIVE2D_DISPLAY
        ? AXON_LIVE2D_DISPLAY_SCALE
        : (IS_MVER ? Math.min(1, MVER_L2D_CORRECT) : 1);
      this.scale = containScale * safeCorrect;
      const rawExtraX = width - nativeWidth * this.scale;
      const rawExtraY = height - nativeHeight * this.scale;
      const offsetXRatio = IS_MVER ? (Number(MVER_L2D_OFFSET[0]) || 0) : 0;
      const offsetYRatio = IS_MVER ? (Number(MVER_L2D_OFFSET[1]) || 0) : 0;
      if (AXON_LIVE2D_DISPLAY) {
        // Dedicated display mode deliberately supports zoom above 100%. Keep the model centered
        // when it becomes larger than the viewport instead of pinning the crop to the top-left.
        this.offsetX = rawExtraX * 0.5 + offsetXRatio * width + AXON_LIVE2D_DISPLAY_OFFSET_X * width * 0.5;
        this.offsetY = rawExtraY * 0.5 + offsetYRatio * height + AXON_LIVE2D_DISPLAY_OFFSET_Y * height * 0.5;
      } else {
        const extraX = Math.max(0, rawExtraX);
        const extraY = Math.max(0, rawExtraY);
        const desiredX = extraX * 0.5 + offsetXRatio * width;
        const desiredY = extraY * 0.5 + offsetYRatio * height;
        // Clamp the authored offset to the remaining letterbox space so the full Cubism canvas
        // is always inside the Android WebView. This specifically prevents top-edge ear clipping.
        this.offsetX = Math.max(0, Math.min(extraX, desiredX));
        this.offsetY = Math.max(0, Math.min(extraY, desiredY));
      }
    }

    setDragTarget(x, y) {
      this.dragTargetX = Math.max(-1, Math.min(1, Number(x) || 0));
      this.dragTargetY = Math.max(-1, Math.min(1, Number(y) || 0));
    }

    resetDrag() {
      this.dragTargetX = 0;
      this.dragTargetY = 0;
      this.dragX = 0;
      this.dragY = 0;
      this.dragVX = 0;
      this.dragVY = 0;
      this.dragLastTime = 0;
      this.dragUserTime = 0;
    }

    updateDragTarget(deltaSeconds) {
      if (!IS_MVER) return;
      const frameRate = 30;
      const epsilon = 0.01;
      const maxV = (40 / 10) / frameRate;
      this.dragUserTime += Math.max(0, Number(deltaSeconds) || 0);
      if (this.dragLastTime === 0) {
        this.dragLastTime = this.dragUserTime;
        return;
      }
      const deltaWeight = (this.dragUserTime - this.dragLastTime) * frameRate;
      this.dragLastTime = this.dragUserTime;
      const maxA = deltaWeight * maxV / (0.15 * frameRate);
      const dx = this.dragTargetX - this.dragX;
      const dy = this.dragTargetY - this.dragY;
      if (Math.abs(dx) <= epsilon && Math.abs(dy) <= epsilon) return;
      const distance = Math.hypot(dx, dy);
      if (distance <= 0 || maxA <= 0) return;
      const targetVX = maxV * dx / distance;
      const targetVY = maxV * dy / distance;
      let ax = targetVX - this.dragVX;
      let ay = targetVY - this.dragVY;
      const acceleration = Math.hypot(ax, ay);
      if (acceleration > maxA) {
        ax *= maxA / acceleration;
        ay *= maxA / acceleration;
      }
      this.dragVX += ax;
      this.dragVY += ay;
      const maxVelocity = 0.5 * (Math.sqrt(maxA * maxA + 8 * maxA * distance) - maxA);
      const currentVelocity = Math.hypot(this.dragVX, this.dragVY);
      if (currentVelocity > maxVelocity && currentVelocity > 0) {
        this.dragVX *= maxVelocity / currentVelocity;
        this.dragVY *= maxVelocity / currentVelocity;
      }
      this.dragX += this.dragVX;
      this.dragY += this.dragVY;
    }

    applyMverDragLateUpdate() {
      if (!IS_MVER) return;
      this.addValue('ParamAngleX', this.dragX * 30, 1);
      this.addValue('ParamAngleY', this.dragY * 30, 1);
      this.addValue('ParamAngleZ', this.dragX * this.dragY * -30, 1);
      this.addValue('ParamBodyAngleX', this.dragX * 10, 1);
      this.addValue('ParamEyeBallX', this.dragX, 1);
      this.addValue('ParamEyeBallY', this.dragY, 1);
    }

    range(id) {
      return this.paramRanges.get(id) || null;
    }

    isParameterLocked(id) {
      const index = this.paramIndex.get(String(id || ''));
      return index !== undefined && this.locks.has(index);
    }

    clamp(index, value) {
      return Math.max(
        this.parameters.minimumValues[index],
        Math.min(this.parameters.maximumValues[index], Number(value) || 0),
      );
    }

    setOverride(id, value) {
      const index = this.paramIndex.get(id);
      if (index === undefined) return;
      this.overrides.set(index, this.clamp(index, value));
    }

    clearOverride(id) {
      const index = this.paramIndex.get(id);
      if (index === undefined) return;
      this.overrides.delete(index);
    }

    setNormalizedLock(id, normalized) {
      const index = this.paramIndex.get(String(id || ''));
      if (index === undefined) return false;
      const ratio = Math.max(0, Math.min(1, Number(normalized) || 0));
      const min = this.parameters.minimumValues[index];
      const max = this.parameters.maximumValues[index];
      this.locks.set(index, this.clamp(index, min + (max - min) * ratio));
      return true;
    }

    clearLock(id) {
      const index = this.paramIndex.get(String(id || ''));
      if (index === undefined) return false;
      this.locks.delete(index);
      return true;
    }

    applyLocks() {
      for (const [index, value] of this.locks) this.parameters.values[index] = value;
    }

    parameterDebug(id) {
      const parameterId = String(id || '');
      const index = this.paramIndex.get(parameterId);
      if (index === undefined) return null;
      const min = Number(this.parameters.minimumValues[index]) || 0;
      const max = Number(this.parameters.maximumValues[index]) || 0;
      const def = Number(this.parameters.defaultValues[index]) || 0;
      const value = Number(this.parameters.values[index]) || 0;
      const span = Math.max(0.000001, max - min);
      let source = 'BASE';
      if (this.locks.has(index)) source = 'LOCK';
      else if (this.trackingOwners.has(index)) source = String(this.trackingOwners.get(index) || 'TRACKING').toUpperCase();
      else if (this.overrides.has(index)) source = 'MANUAL';
      else if (this.frameInputs.has(index)) source = 'INPUT';
      else if (MOUSE_X_PARAMETER_IDS.includes(parameterId) || MOUSE_Y_PARAMETER_IDS.includes(parameterId)
          || MOUSE_LEFT_PARAMETER_IDS.includes(parameterId) || MOUSE_RIGHT_PARAMETER_IDS.includes(parameterId)
          || parameterId === 'ParamMouseLeftDown' || parameterId === 'ParamMouseRightDown'
          || parameterId === 'ParamMouseRihgtDown') source = 'INPUT';
      else if (this.physicsOutputDeltas.has(index) && Math.abs(Number(this.physicsOutputDeltas.get(index)) || 0) > 1e-7) source = 'PHYSICS';
      else {
        const forcedExpression = state.debugExpressionKind === 'live2d' && state.debugExpressionIndex >= 0
          ? state.debugExpressionIndex : state.mverExpressionIndex;
        const expression = MVER_EXPRESSIONS[Math.max(0, forcedExpression | 0)];
        if (expression && Array.isArray(expression.Parameters)
            && expression.Parameters.some((item) => String(item && item.Id || '') === parameterId)) source = 'EXPRESSION';
        else if (this.motionTouchedParameters.has(index)) source = 'MOTION';
        else if (LIVE2D_EYE_BLINK_IDS.includes(parameterId) || parameterId === 'ParamEyeLOpen' || parameterId === 'ParamEyeROpen') source = 'BLINK';
        else if (parameterId === 'ParamBreath' || parameterId === 'ParamAngleX' || parameterId === 'ParamAngleY'
            || parameterId === 'ParamAngleZ' || parameterId === 'ParamBodyAngleX') source = 'IDLE';
      }
      return {
        id: parameterId, value, min, max, defaultValue: def,
        normalized: Math.max(0, Math.min(1, (value - min) / span)),
        source, locked: this.locks.has(index),
        lockNormalized: this.locks.has(index) ? Math.max(0, Math.min(1, (this.locks.get(index) - min) / span)) : null,
      };
    }

    clearOverrides() {
      this.overrides.clear();
      this.frameInputs.clear();
      this.trackingTargets.clear();
      this.trackingValues.clear();
      this.trackingFinal.clear();
      this.trackingOwners.clear();
      this.trackingPriorities.clear();
    }

    setFrameInput(id, value) {
      const index = this.paramIndex.get(id);
      if (index === undefined) return;
      this.frameInputs.set(index, this.clamp(index, value));
    }

    clearFrameInput(id) {
      const index = this.paramIndex.get(id);
      if (index !== undefined) this.frameInputs.delete(index);
    }

    setTrackingTarget(id, value, finalPass = true, owner = 'generic', priority = 0) {
      const index = this.paramIndex.get(id);
      if (index === undefined) return false;
      const incomingOwner = String(owner || 'generic');
      const incomingPriority = Number(priority) || 0;
      const existingOwner = this.trackingOwners.get(index);
      const existingPriority = this.trackingPriorities.get(index) || 0;
      // Asynchronous camera paths do not arrive in a deterministic order. Never allow a lower
      // priority source to steal a target just because its callback happened later in the frame.
      if (existingOwner !== undefined && existingOwner !== incomingOwner
          && existingPriority > incomingPriority) return false;
      const clamped = this.clamp(index, value);
      this.trackingTargets.set(index, clamped);
      this.trackingOwners.set(index, incomingOwner);
      this.trackingPriorities.set(index, incomingPriority);
      if (!this.trackingValues.has(index)) this.trackingValues.set(index, clamped);
      if (finalPass) this.trackingFinal.add(index);
      else this.trackingFinal.delete(index);
      return true;
    }

    clearTrackingTarget(id, owner = null) {
      const index = this.paramIndex.get(id);
      if (index === undefined) return false;
      if (owner !== null && this.trackingOwners.get(index) !== String(owner)) return false;
      // Source-specific releases are eased back toward the authored base instead of deleting the
      // interpolated value in one frame. A higher-priority/new capture source can still take over
      // immediately because the temporary release owner has the lowest possible priority.
      if (owner !== null && this.trackingValues.has(index)) {
        const base = this.savedParameters[index] ?? this.defaults[index] ?? 0;
        this.trackingTargets.set(index, this.clamp(index, base));
        this.trackingOwners.set(index, 'release');
        this.trackingPriorities.set(index, -100);
        this.trackingFinal.delete(index);
        return true;
      }
      this.trackingTargets.delete(index);
      this.trackingValues.delete(index);
      this.trackingFinal.delete(index);
      this.trackingOwners.delete(index);
      this.trackingPriorities.delete(index);
      return true;
    }

    trackingRate(index, current, target) {
      const id = String(this.parameters.ids[index] || '');
      const min = this.parameters.minimumValues[index];
      const max = this.parameters.maximumValues[index];
      const span = Math.max(0.0001, max - min);
      const normalizedDelta = Math.min(1, Math.abs(target - current) / span * 5);
      // Small camera noise gets heavy damping; intentional movement automatically accelerates.
      // This keeps blinks/mouth responsive without letting sub-pixel landmark shimmer shake model.
      let slow = 7, fast = 15;
      if (id === 'ParamEyeLOpen' || id === 'ParamEyeROpen') {
        // Blink closing must be almost immediate; reopening is slightly softer so landmark shimmer
        // does not make the eyelid chatter around fully-open.
        slow = target < current ? 18 : 13;
        fast = target < current ? 42 : 30;
      } else if (id.includes('EyeBall')) {
        slow = 12; fast = 26;
      } else if (id.includes('Mouth') || id.includes('Jaw') || id.includes('guzui') || id.includes('tushe')) {
        slow = 12; fast = 31;
      } else if (id.includes('Eye')) {
        slow = 11; fast = 27;
      } else if (id.includes('Brow')) {
        slow = 9; fast = 20;
      } else if (id.includes('Angle') || id.includes('Body')) {
        slow = 7; fast = 17;
      }
      return slow + (fast - slow) * normalizedDelta;
    }

    updateTrackingInputs(deltaSeconds) {
      const dt = Math.max(0, Math.min(0.05, Number(deltaSeconds) || 0));
      for (const [index, target] of this.trackingTargets) {
        const current = this.trackingValues.has(index) ? this.trackingValues.get(index) : target;
        const min = this.parameters.minimumValues[index];
        const max = this.parameters.maximumValues[index];
        const span = Math.max(0.0001, max - min);
        // Ignore microscopic target churn from camera quantization. Release targets are removed
        // once they have converged so authored motion regains complete control without a hard snap.
        if (Math.abs(target - current) < span * 0.0009) {
          if (this.trackingOwners.get(index) === 'release') {
            this.trackingTargets.delete(index);
            this.trackingValues.delete(index);
            this.trackingFinal.delete(index);
            this.trackingOwners.delete(index);
            this.trackingPriorities.delete(index);
          }
          continue;
        }
        const rate = this.trackingOwners.get(index) === 'release'
          ? Math.min(8, this.trackingRate(index, current, target))
          : this.trackingRate(index, current, target);
        const alpha = 1 - Math.exp(-rate * dt);
        this.trackingValues.set(index, this.clamp(index, current + (target - current) * alpha));
      }
    }

    applyTrackingInputs(finalOnly = false) {
      for (const [index, value] of this.trackingValues) {
        if (finalOnly && !this.trackingFinal.has(index)) continue;
        this.parameters.values[index] = value;
      }
    }

    hasTrackingTarget(id) {
      const index = this.paramIndex.get(id);
      return index !== undefined && this.trackingTargets.has(index);
    }

    hasFaceEyeTrackingTarget() {
      const ids = LIVE2D_EYE_BLINK_IDS.length
        ? LIVE2D_EYE_BLINK_IDS
        : ['ParamEyeLOpen', 'ParamEyeROpen'];
      for (const id of ids) {
        const index = this.paramIndex.get(id);
        if (index !== undefined
            && this.trackingTargets.has(index)
            && this.trackingOwners.get(index) === 'expression') return true;
      }
      // A model may declare no EyeBlink group but still use the standard ids.
      return this.hasTrackingTarget('ParamEyeLOpen') || this.hasTrackingTarget('ParamEyeROpen');
    }

    setEyeBlink(value) {
      this.eyeBlink = Math.max(0, Math.min(1, value));
    }

    setValue(id, value) {
      const index = this.paramIndex.get(id);
      if (index === undefined) return;
      this.parameters.values[index] = this.clamp(index, value);
    }

    setFrameActive(id, active) {
      const index = this.paramIndex.get(String(id || ''));
      if (index === undefined) return;
      if (!active) { this.frameInputs.delete(index); return; }
      this.frameInputs.set(index, this.parameters.maximumValues[index]);
    }

    setBooleanValue(id, active) {
      const index = this.paramIndex.get(String(id || ''));
      if (index === undefined) return;
      this.parameters.values[index] = active
        ? this.parameters.maximumValues[index]
        : this.parameters.defaultValues[index];
    }

    setNormalizedOverride(id, normalized) {
      const index = this.paramIndex.get(String(id || ''));
      if (index === undefined) return false;
      const ratio = Math.max(0, Math.min(1, Number(normalized) || 0));
      const min = this.parameters.minimumValues[index];
      const max = this.parameters.maximumValues[index];
      this.overrides.set(index, this.clamp(index, min + (max - min) * ratio));
      return true;
    }

    addValue(id, value, weight) {
      const index = this.paramIndex.get(id);
      if (index === undefined) return;
      this.parameters.values[index] = this.clamp(
        index,
        this.parameters.values[index] + value * weight,
      );
    }

    applyMotionIndex(index, startedAt, now = performance.now()) {
      const resolved = Number(index) | 0;
      if (resolved < 0 || resolved >= MVER_MOTIONS.length) return false;
      const item = MVER_MOTIONS[resolved];
      const motion = item && item.json && typeof item.json === 'object' ? item.json : null;
      if (!motion) return false;
      const meta = motion.Meta || {};
      const duration = Math.max(0, Number(meta.Duration) || 0);
      let localTime = Math.max(0, (now - Math.max(0, Number(startedAt) || now)) / 1000);
      if (meta.Loop === true && duration > 0) localTime %= duration;
      else if (duration > 0) localTime = Math.min(duration, localTime);
      const curves = Array.isArray(motion.Curves) ? motion.Curves : [];

      // Cubism motion "Model" curves are effect controls, not ordinary parameters. Supporting
      // them matters for exported motions that animate blink/lip-sync/model opacity indirectly.
      let motionEyeBlink = null;
      let motionLipSync = null;
      for (const curve of curves) {
        if (String(curve && curve.Target || '').toLowerCase() !== 'model') continue;
        const id = String(curve.Id || '').toLowerCase();
        const value = evaluateMotionCurve(curve, localTime);
        if (id === 'eyeblink') motionEyeBlink = value;
        else if (id === 'lipsync') motionLipSync = value;
        else if (id === 'opacity') this.modelOpacity = Math.max(0, Math.min(1, value));
      }

      const blinkIds = new Set(LIVE2D_EYE_BLINK_IDS.map(String));
      const lipIds = new Set(LIVE2D_LIP_SYNC_IDS.map(String));
      const touchedBlink = new Set();
      const touchedLip = new Set();
      for (const curve of curves) {
        const target = String(curve.Target || '');
        const id = String(curve.Id || '');
        if (target.toLowerCase() === 'model') continue;
        let value = evaluateMotionCurve(curve, localTime);
        const weight = motionFadeWeight(curve, meta, localTime, duration);
        if (target === 'Parameter') {
          const parameterIndex = this.paramIndex.get(id);
          if (parameterIndex === undefined) continue;
          if (motionEyeBlink !== null && blinkIds.has(id)) {
            value *= motionEyeBlink;
            touchedBlink.add(id);
          }
          if (motionLipSync !== null && lipIds.has(id)) {
            value += motionLipSync;
            touchedLip.add(id);
          }
          const current = this.parameters.values[parameterIndex];
          this.motionTouchedParameters.add(parameterIndex);
          this.parameters.values[parameterIndex] = this.clamp(
            parameterIndex, current + (value - current) * weight,
          );
        } else if (target === 'PartOpacity' && this.model.parts && this.model.parts.opacities) {
          const partIndex = this.partIndex.get(id);
          if (partIndex === undefined) continue;
          const current = this.model.parts.opacities[partIndex];
          this.model.parts.opacities[partIndex] = Math.max(0, Math.min(1, current + (value - current) * weight));
        }
      }

      // The official Cubism motion layer applies Model/EyeBlink and Model/LipSync curves to
      // configured effect IDs even when there is no explicit Parameter curve for that ID.
      if (motionEyeBlink !== null) {
        for (const id of blinkIds) {
          if (touchedBlink.has(id)) continue;
          const parameterIndex = this.paramIndex.get(id);
          if (parameterIndex === undefined) continue;
          this.motionTouchedParameters.add(parameterIndex);
          this.parameters.values[parameterIndex] = this.clamp(
            parameterIndex, this.parameters.values[parameterIndex] * motionEyeBlink,
          );
        }
      }
      if (motionLipSync !== null) {
        for (const id of lipIds) {
          if (touchedLip.has(id)) continue;
          this.addValue(id, motionLipSync, 1);
        }
      }
      return true;
    }

    idleGroup() {
      const exact = mverMotionGroup('Idle');
      if (exact.length) return exact;
      // Some exporters use Idle_1 / idle_motion instead of the canonical Idle group.
      for (const [name, value] of Object.entries(MVER_MOTION_GROUPS)) {
        if (/^idle(?:[_ -].*)?$/i.test(String(name)) && Array.isArray(value) && value.length) return value;
      }
      return [];
    }

    ensureIdleMotion(now = performance.now()) {
      if (state.mverMotionIndex >= 0) return -1;
      const group = this.idleGroup();
      if (!group.length) return -1;
      let expired = this.idleMotionIndex < 0;
      if (!expired) {
        const duration = motionDurationSeconds(this.idleMotionIndex);
        const item = MVER_MOTIONS[this.idleMotionIndex];
        const loop = Boolean(item && item.json && item.json.Meta && item.json.Meta.Loop === true);
        if (!loop && duration > 0 && (now - this.idleMotionStartedAt) / 1000 >= duration) expired = true;
      }
      if (expired) {
        const valid = group.map(Number).filter((value) => Number.isInteger(value)
          && value >= 0 && value < MVER_MOTIONS.length);
        if (!valid.length) return -1;
        let next = valid[Math.floor(Math.random() * valid.length)];
        if (valid.length > 1 && next === this.lastIdleMotionIndex) {
          next = valid[(valid.indexOf(next) + 1) % valid.length];
        }
        this.idleMotionIndex = next;
        this.lastIdleMotionIndex = next;
        this.idleMotionStartedAt = now;
      }
      return this.idleMotionIndex;
    }

    applyMverMotions(now = performance.now()) {
      if (state.mverMotionIndex >= 0) {
        return this.applyMotionIndex(state.mverMotionIndex, state.mverMotionStartedAt, now);
      }
      const idle = this.ensureIdleMotion(now);
      if (idle >= 0) return this.applyMotionIndex(idle, this.idleMotionStartedAt, now);
      return false;
    }

    createPoseState() {
      if (!LIVE2D_POSE || !Array.isArray(LIVE2D_POSE.Groups) || !this.model.parts) return null;
      const groups = [];
      for (const rawGroup of LIVE2D_POSE.Groups) {
        if (!Array.isArray(rawGroup)) continue;
        const items = [];
        for (const rawItem of rawGroup) {
          if (!rawItem || typeof rawItem !== 'object') continue;
          const id = String(rawItem.Id || rawItem.id || '');
          if (!id) continue;
          const partIndex = this.partIndex.get(id);
          const parameterIndex = this.paramIndex.get(id);
          const links = Array.isArray(rawItem.Link || rawItem.link)
            ? (rawItem.Link || rawItem.link).map(String).map((linkId) => this.partIndex.get(linkId))
              .filter((index) => index !== undefined)
            : [];
          items.push({ id, partIndex, parameterIndex, links });
        }
        if (items.length) groups.push(items);
      }
      if (!groups.length) return null;
      const configuredFade = Number(LIVE2D_POSE.FadeInTime ?? LIVE2D_POSE.fadeInTime);
      return {
        groups,
        fadeTime: Number.isFinite(configuredFade) && configuredFade > 0 ? configuredFade : 0.5,
        initialized: false,
      };
    }

    resetPose() {
      const pose = this.poseState;
      if (!pose || !this.model.parts || !this.model.parts.opacities) return;
      for (const group of pose.groups) {
        let firstVisible = group.findIndex((item) => item.partIndex !== undefined);
        if (firstVisible < 0) continue;
        for (let i = 0; i < group.length; i++) {
          const item = group[i];
          const visible = i === firstVisible ? 1 : 0;
          if (item.partIndex !== undefined) this.model.parts.opacities[item.partIndex] = visible;
          if (item.parameterIndex !== undefined) this.parameters.values[item.parameterIndex] = visible;
          for (const linkPartIndex of item.links) this.model.parts.opacities[linkPartIndex] = visible;
        }
      }
      pose.initialized = true;
    }

    applyPose(deltaSeconds) {
      const pose = this.poseState;
      if (!pose || !this.model.parts || !this.model.parts.opacities) return;
      if (!pose.initialized) this.resetPose();
      const epsilon = 0.001;
      const phi = 0.5;
      const backOpacityThreshold = 0.15;
      const dt = Math.max(0, Number(deltaSeconds) || 0);
      for (const group of pose.groups) {
        let visibleIndex = -1;
        let newOpacity = 1;
        for (let i = 0; i < group.length; i++) {
          const item = group[i];
          if (item.partIndex === undefined || item.parameterIndex === undefined) continue;
          if (this.parameters.values[item.parameterIndex] > epsilon) {
            visibleIndex = i;
            newOpacity = Math.min(1, this.model.parts.opacities[item.partIndex] + dt / pose.fadeTime);
            break;
          }
        }
        if (visibleIndex < 0) {
          visibleIndex = group.findIndex((item) => item.partIndex !== undefined);
          if (visibleIndex < 0) continue;
          newOpacity = 1;
        }
        for (let i = 0; i < group.length; i++) {
          const item = group[i];
          if (item.partIndex === undefined) continue;
          if (i === visibleIndex) {
            this.model.parts.opacities[item.partIndex] = newOpacity;
          } else {
            let opacity = this.model.parts.opacities[item.partIndex];
            let a1 = newOpacity < phi
              ? (newOpacity * (phi - 1)) / phi + 1
              : ((1 - newOpacity) * phi) / (1 - phi);
            const backOpacity = (1 - a1) * (1 - newOpacity);
            if (backOpacity > backOpacityThreshold && newOpacity < 1) {
              a1 = 1 - backOpacityThreshold / (1 - newOpacity);
            }
            if (opacity > a1) opacity = a1;
            this.model.parts.opacities[item.partIndex] = Math.max(0, Math.min(1, opacity));
          }
        }
      }
      // CubismPose links copy the source part opacity after every group fade.
      for (const group of pose.groups) {
        for (const item of group) {
          if (item.partIndex === undefined) continue;
          const opacity = this.model.parts.opacities[item.partIndex];
          for (const linkPartIndex of item.links) this.model.parts.opacities[linkPartIndex] = opacity;
        }
      }
    }

    applyEyeBlink(value) {
      const ids = LIVE2D_EYE_BLINK_IDS.length
        ? LIVE2D_EYE_BLINK_IDS
        : ['ParamEyeLOpen', 'ParamEyeROpen'];
      for (const id of ids) this.setValue(id, value);
    }

    createPhysicsStates() {
      const settings = MVER_PHYSICS && Array.isArray(MVER_PHYSICS.PhysicsSettings)
        ? MVER_PHYSICS.PhysicsSettings : [];
      return settings.map((setting) => {
        const vertices = Array.isArray(setting.Vertices) ? setting.Vertices : [];
        let y = 0;
        return vertices.map((vertex, index) => {
          if (index > 0) y += Math.max(0, Number(vertex && vertex.Radius) || 0);
          return {
            x: 0, y,
            lastX: 0, lastY: y,
            velocityX: 0, velocityY: 0,
            lastGravityX: 0, lastGravityY: 1,
          };
        });
      });
    }

    directionToRadian(fromX, fromY, toX, toY) {
      const from = Math.atan2(fromY, fromX);
      const to = Math.atan2(toY, toX);
      let result = to - from;
      while (result < -Math.PI) result += Math.PI * 2;
      while (result > Math.PI) result -= Math.PI * 2;
      return result;
    }

    createPhysicsOutputIndices() {
      const result = new Set();
      if (!MVER_PHYSICS || !Array.isArray(MVER_PHYSICS.PhysicsSettings)) return result;
      for (const setting of MVER_PHYSICS.PhysicsSettings) {
        const outputs = setting && Array.isArray(setting.Output) ? setting.Output : [];
        for (const output of outputs) {
          const id = output && output.Destination ? String(output.Destination.Id || '') : '';
          const index = this.paramIndex.get(id);
          if (index !== undefined) result.add(index);
        }
      }
      return result;
    }

    invalidatePhysicsControlState(resetParticles) {
      if (resetParticles) {
        this.physicsStates = this.createPhysicsStates();
        this.physicsActionUntil = 0;
      }
      this.physicsOutputDeltas.clear();
      this.physicsAccumulator = 0;
    }

    /**
     * Trigger one Cubism Physics group without changing its enabled state. The impulse is injected
     * into particle velocity, so it travels through the model's authored radius/delay/mobility and
     * naturally damps out. Repeated presses alternate direction to stay visible without drift.
     */
    triggerPhysicsGroupAction(groupKey, magnitude = 1) {
      if (!MVER_PHYSICS || !this.physicsStates.length) return false;
      const key = String(groupKey || '');
      const settingIndex = PHYSICS_GROUP_KEYS.indexOf(key);
      if (settingIndex < 0) return false;
      const control = physicsControlFor(settingIndex);
      const authoredStrength = physicsControls.globalStrength * control.strength;
      if (!control.enabled || authoredStrength <= 0) return false;
      const particles = this.physicsStates[settingIndex];
      if (!particles || particles.length < 2) return false;
      const settings = Array.isArray(MVER_PHYSICS.PhysicsSettings) ? MVER_PHYSICS.PhysicsSettings : [];
      const setting = settings[settingIndex] || {};
      const vertices = Array.isArray(setting.Vertices) ? setting.Vertices : [];
      const amount = Math.max(0.2, Math.min(2, Number(magnitude) || 1));
      const strength = Math.max(0.25, Math.min(2, authoredStrength));
      const direction = (++this.physicsActionSequence & 1) ? 1 : -1;
      const tail = Math.max(1, particles.length - 1);
      for (let i = 1; i < particles.length; i++) {
        const particle = particles[i];
        const vertex = vertices[i] || {};
        const radius = Math.max(0.0001, Number(vertex.Radius) || 1);
        const phase = i / tail;
        const impulse = radius * amount * strength * (0.32 + phase * 0.38);
        particle.velocityX += direction * impulse;
        // A small vertical component prevents perfectly horizontal chains from looking robotic.
        particle.velocityY += impulse * (0.05 + 0.05 * (1 - phase));
      }
      this.physicsActionUntil = performance.now() + 900;
      this.physicsAccumulator = Math.max(this.physicsAccumulator, 1 / Math.max(1, PHYSICS_ACTIVE_FPS));
      // Never replay a cached pre-action delta on the first action frame.
      this.physicsOutputDeltas.clear();
      return true;
    }

    physicsIsInteractive() {
      const pointerMoving = Math.abs(state.targetX - state.cursorX) > 0.0015
        || Math.abs(state.targetY - state.cursorY) > 0.0015;
      const dragMoving = Math.abs(this.dragVX) > 0.015 || Math.abs(this.dragVY) > 0.015;
      return state.mverPressed.size > 0 || state.modernPressed.size > 0
        || state.modernGamepadPressed.size > 0 || state.mouseButtons !== 0
        || state.parameterPulseUntil.size > 0 || state.parameterSequenceEvents.length > 0
        || state.mverMotionIndex >= 0 || performance.now() < this.physicsActionUntil
        || pointerMoving || dragMoving;
    }

    applyAdaptivePhysics(deltaSeconds) {
      if (!MVER_PHYSICS || !this.physicsStates.length) return;
      if (physicsControls.globalStrength <= 0) {
        this.physicsAccumulator = 0;
        this.physicsOutputDeltas.clear();
        return;
      }
      const targetFps = this.physicsIsInteractive() ? PHYSICS_ACTIVE_FPS : PHYSICS_IDLE_FPS;
      const interval = 1 / Math.max(1, targetFps);
      this.physicsAccumulator = Math.min(0.12, this.physicsAccumulator + Math.max(0, Number(deltaSeconds) || 0));
      if (this.physicsAccumulator + 1e-6 < interval && this.physicsOutputDeltas.size) {
        // Reapply the previous physics delta over this frame's fresh motion/expression base. Caching
        // absolute values would incorrectly overwrite a motion while physics is on a skipped frame.
        for (const [index, delta] of this.physicsOutputDeltas) {
          this.parameters.values[index] = this.clamp(index, this.parameters.values[index] + delta);
        }
        return;
      }
      const before = new Map();
      for (const index of this.physicsOutputIndices) before.set(index, this.parameters.values[index]);
      const step = Math.max(1 / 240, Math.min(0.1, this.physicsAccumulator || deltaSeconds || interval));
      this.physicsAccumulator = 0;
      this.applyMverPhysics(step);
      this.physicsOutputDeltas.clear();
      for (const [index, value] of before) {
        this.physicsOutputDeltas.set(index, this.parameters.values[index] - value);
      }
    }

    applyMverPhysics(deltaSeconds) {
      if (!MVER_PHYSICS || !this.physicsStates.length) return;
      const settings = Array.isArray(MVER_PHYSICS.PhysicsSettings) ? MVER_PHYSICS.PhysicsSettings : [];
      const forces = MVER_PHYSICS.Meta && MVER_PHYSICS.Meta.EffectiveForces
        ? MVER_PHYSICS.Meta.EffectiveForces : {};
      const gravity = forces.Gravity || { X: 0, Y: -1 };
      const wind = forces.Wind || { X: 0, Y: 0 };
      const parentGravityX = Number(gravity.X) || 0;
      const parentGravityY = Number.isFinite(Number(gravity.Y)) ? Number(gravity.Y) : -1;
      const windX = Number(wind.X) || 0;
      const windY = Number(wind.Y) || 0;
      const dt = Math.max(1 / 240, Math.min(0.1, Number(deltaSeconds) || 1 / 60));
      const airResistance = 5;

      for (let settingIndex = 0; settingIndex < settings.length; settingIndex++) {
        const setting = settings[settingIndex] || {};
        const control = physicsControlFor(settingIndex);
        const strength = physicsControls.globalStrength * control.strength;
        if (!control.enabled || strength <= 0) continue;
        const particles = this.physicsStates[settingIndex];
        if (!particles || particles.length < 2) continue;
        const normalization = setting.Normalization || {};
        let tx = 0, ty = 0, angle = 0;
        const inputs = Array.isArray(setting.Input) ? setting.Input : [];
        for (const input of inputs) {
          const source = input && input.Source ? String(input.Source.Id || '') : '';
          const parameterIndex = this.paramIndex.get(source);
          if (parameterIndex === undefined) continue;
          const type = String(input.Type || 'X');
          const norm = type === 'Angle' ? normalization.Angle : normalization.Position;
          let normalized = normalizePhysicsValue(
            this.parameters.values[parameterIndex],
            this.parameters.minimumValues[parameterIndex],
            this.parameters.maximumValues[parameterIndex],
            this.parameters.defaultValues[parameterIndex],
            norm,
          );
          // Cubism Physics uses Reflect=false as the inverted input direction.
          if (input.Reflect !== true) normalized = -normalized;
          normalized *= Math.max(0, Number(input.Weight) || 0) / 100;
          if (type === 'Angle') angle += normalized;
          else if (type === 'Y') ty += normalized;
          else tx += normalized;
        }

        const radAngle = -angle * Math.PI / 180;
        [tx, ty] = rotateVector(tx, ty, radAngle);
        particles[0].x = tx;
        particles[0].y = ty;

        // CubismPhysics::RadianToDirection(0) points up in the physics coordinate system.
        const gravityAngle = angle * Math.PI / 180;
        let currentGravityX = Math.sin(gravityAngle);
        let currentGravityY = Math.cos(gravityAngle);
        const gravityLength = Math.max(0.000001, Math.hypot(currentGravityX, currentGravityY));
        currentGravityX /= gravityLength;
        currentGravityY /= gravityLength;
        const vertices = Array.isArray(setting.Vertices) ? setting.Vertices : [];
        const positionNorm = normalization.Position || {};
        const threshold = 0.001 * Math.max(1, Math.abs(Number(positionNorm.Maximum) || 1));

        for (let i = 1; i < particles.length; i++) {
          const particle = particles[i];
          const previous = particles[i - 1];
          const vertex = vertices[i] || {};
          const lastX = particle.x;
          const lastY = particle.y;
          const delay = Math.max(0, Number(vertex.Delay) || 0) * dt * 30;
          const mobility = Math.max(0, Number(vertex.Mobility) || 0);
          const acceleration = Math.max(0, Number(vertex.Acceleration) || 0);
          const radius = Math.max(0.0001, Number(vertex.Radius) || 0.0001);

          let directionX = particle.x - previous.x;
          let directionY = particle.y - previous.y;
          const gravityDelta = this.directionToRadian(
            particle.lastGravityX, particle.lastGravityY,
            currentGravityX, currentGravityY,
          ) / airResistance;
          [directionX, directionY] = rotateVector(directionX, directionY, gravityDelta);

          let nextX = previous.x + directionX;
          let nextY = previous.y + directionY;
          nextX += particle.velocityX * delay
            + (currentGravityX * acceleration + windX) * delay * delay;
          nextY += particle.velocityY * delay
            + (currentGravityY * acceleration + windY) * delay * delay;

          let segmentX = nextX - previous.x;
          let segmentY = nextY - previous.y;
          const segmentLength = Math.max(0.000001, Math.hypot(segmentX, segmentY));
          segmentX = segmentX / segmentLength * radius;
          segmentY = segmentY / segmentLength * radius;
          nextX = previous.x + segmentX;
          nextY = previous.y + segmentY;
          if (Math.abs(nextX) < threshold) nextX = 0;

          particle.lastX = lastX;
          particle.lastY = lastY;
          particle.x = nextX;
          particle.y = nextY;
          if (delay > 0) {
            particle.velocityX = (nextX - lastX) / delay * mobility;
            particle.velocityY = (nextY - lastY) / delay * mobility;
          } else {
            particle.velocityX = 0;
            particle.velocityY = 0;
          }
          particle.lastGravityX = currentGravityX;
          particle.lastGravityY = currentGravityY;
        }

        const outputs = Array.isArray(setting.Output) ? setting.Output : [];
        for (const output of outputs) {
          const vertexIndex = Math.max(1, Number(output.VertexIndex) | 0);
          if (vertexIndex >= particles.length) continue;
          const particle = particles[vertexIndex];
          const previous = particles[vertexIndex - 1];
          const dx = particle.x - previous.x;
          const dy = particle.y - previous.y;
          const type = String(output.Type || 'Angle');
          let outputValue;
          if (type === 'X') {
            outputValue = dx;
          } else if (type === 'Y') {
            outputValue = dy;
          } else {
            let baseX = -parentGravityX;
            let baseY = -parentGravityY;
            if (vertexIndex >= 2) {
              baseX = particles[vertexIndex - 1].x - particles[vertexIndex - 2].x;
              baseY = particles[vertexIndex - 1].y - particles[vertexIndex - 2].y;
            }
            // CubismPhysics Angle output is radians. Converting to degrees here (old runtime)
            // made Scale=40/100 saturate nearly every destination parameter.
            outputValue = this.directionToRadian(baseX, baseY, dx, dy);
          }
          if (output.Reflect === true) outputValue = -outputValue;
          const value = outputValue * (Number(output.Scale) || 0);
          const destination = output && output.Destination ? String(output.Destination.Id || '') : '';
          const parameterIndex = this.paramIndex.get(destination);
          if (parameterIndex === undefined) continue;
          const clamped = this.clamp(parameterIndex, value);
          const weight = Math.max(0, Math.min(1, (Number(output.Weight) || 0) / 100));
          const current = this.parameters.values[parameterIndex];
          // Strength scales the physics contribution relative to the fresh motion/expression base.
          // Values above 100% intentionally allow a stronger response, then clamp to the model's
          // authored parameter range instead of globally stretching every parameter.
          const effectiveWeight = weight * strength;
          this.parameters.values[parameterIndex] = this.clamp(
            parameterIndex, current + (clamped - current) * effectiveWeight,
          );
        }
      }
    }

    applyMverExpression(now = performance.now()) {
      if (!MVER_EXPRESSIONS.length) return;
      const forced = state.debugExpressionKind === 'live2d' && state.debugExpressionIndex >= 0;
      if (!IS_MVER && !forced) return;
      const expressionIndex = forced ? state.debugExpressionIndex : state.mverExpressionIndex;
      if (expressionIndex < 0) return;
      const expression = MVER_EXPRESSIONS[Math.max(0, expressionIndex | 0)];
      if (!expression || typeof expression !== 'object') return;
      const parameters = Array.isArray(expression.Parameters) ? expression.Parameters : [];
      const fadeIn = Math.max(0, Number(expression.FadeInTime) || 0);
      const startedAt = forced ? state.debugExpressionStartedAt : state.mverExpressionStartedAt;
      const elapsed = Math.max(0, (now - Math.max(0, startedAt || now)) / 1000);
      const fadeWeight = fadeIn > 0 ? Math.max(0, Math.min(1, elapsed / fadeIn)) : 1;
      const weight = fadeWeight * (forced ? Math.max(0, Math.min(1, Number(state.debugExpressionWeight) || 0)) : 1);
      for (const item of parameters) {
        const index = this.paramIndex.get(String(item.Id || ''));
        if (index === undefined) continue;
        const value = Number(item.Value) || 0;
        const blend = String(item.Blend || 'Add').toLowerCase();
        const current = this.parameters.values[index];
        let target;
        if (blend === 'overwrite') target = value;
        else if (blend === 'multiply') target = current * value;
        else target = current + value;
        this.parameters.values[index] = this.clamp(index, current + (target - current) * weight);
      }
    }

    prepareParameters(deltaSeconds) {
      this.elapsedSeconds += deltaSeconds;
      const now = performance.now();
      // l2dcat advances CubismTargetPoint before LoadParameters/LateUpdate.
      this.updateDragTarget(deltaSeconds);

      // Cubism's model pipeline starts each frame from SaveParameters(), not from moc defaults.
      // Restoring defaults here erased authored motion continuity and made imported models static.
      for (let i = 0; i < this.parameters.count; i++) {
        this.parameters.values[i] = this.savedParameters[i] ?? this.defaults[i];
      }
      // Base motion track: keyed CAT_motion/CAT_motion_lock takes priority; otherwise play Idle.
      // Cubism skips standalone eye blink while a motion manager is actively updating the model.
      this.motionTouchedParameters.clear();
      const motionUpdated = this.applyMverMotions(now);

      // Long-term API overrides belong to the saved base, matching Cubism's long-term cache.
      for (const [index, value] of this.overrides) this.parameters.values[index] = value;
      for (let i = 0; i < this.parameters.count; i++) this.savedParameters[i] = this.parameters.values[i];
      // Current-frame Mver input. Pointer/key states and camera tracking feed physics every frame,
      // but must never be baked into SaveParameters. Tracking values are interpolated here at the
      // renderer frame rate, removing the old 15-20 Hz visible stepping from Camera2 callbacks.
      for (const [index, value] of this.frameInputs) this.parameters.values[index] = value;
      this.updateTrackingInputs(deltaSeconds);
      this.applyTrackingInputs(false);
      const mouseLeftDown = (state.mouseVisualButtons & 1) ? 1 : 0;
      const mouseRightDown = (state.mouseVisualButtons & 2) ? 1 : 0;
      this.setValue('ParamMouseLeftDown', mouseLeftDown);
      this.setValue('ParamMouseRightDown', mouseRightDown);
      for (const id of MOUSE_LEFT_PARAMETER_IDS) this.setBooleanValue(String(id), mouseLeftDown > 0);
      for (const id of MOUSE_RIGHT_PARAMETER_IDS) this.setBooleanValue(String(id), mouseRightDown > 0);
      // A number of Mver skins shipped with this historical misspelling in the model. Missing
      // parameters are ignored by setValue, so feeding both names is safe for correct models.
      this.setValue('ParamMouseRihgtDown', mouseRightDown);

      // l2dcat/Cubism late updater order: blink (only without active motion) -> expression
      // -> look/drag -> breath -> physics.
      if (!motionUpdated && !this.hasFaceEyeTrackingTarget()) this.applyEyeBlink(this.eyeBlink);
      this.applyMverExpression(now);
      if (IS_MVER) this.applyMverDragLateUpdate();

      const t = this.elapsedSeconds * 2 * Math.PI;
      // Match l2dcat's Bongo Cat Mver BreathParameterData exactly. These are additive
      // contributions, not replacements, so authored motion/expression values survive.
      this.addValue('ParamAngleX', 15 * Math.sin(t / 6.5345), 0.5);
      this.addValue('ParamAngleY', 8 * Math.sin(t / 3.5345), 0.5);
      this.addValue('ParamAngleZ', 10 * Math.sin(t / 5.5345), 0.5);
      this.addValue('ParamBodyAngleX', 4 * Math.sin(t / 15.5345), 0.5);
      this.addValue('ParamBreath', 0.5 + 0.5 * Math.sin(t / 3.2345), 0.5);

      this.applyAdaptivePhysics(deltaSeconds);
      // Cubism framework applies Pose after Physics/LipSync and before model.update().
      this.applyPose(deltaSeconds);
      // Face/head channels are direct performer controls. Reapply them after blink/expression/physics
      // so authored idle effects cannot wipe out real eye, brow, lip or profile tracking.
      this.applyTrackingInputs(true);
      // Parameter locks are a debug final-pass override: Motion/Expression/Physics/Tracking may all
      // continue updating internally, but the selected parameter remains fixed on the rendered model.
      this.applyLocks();
      // Watermark suppression is intentionally last. Hide both the declared toggle parameter and
      // any DisplayInfo parts explicitly named 水印/watermark; this fixes models where the visible
      // artwork is a dedicated part rather than a pure parameter switch.
      if (AXON_LIVE2D_HIDE_WATERMARK) {
        for (const id of AXON_LIVE2D_WATERMARK_PARAMETERS) {
          const index = this.paramIndex.get(id);
          if (index !== undefined) {
            const min = this.parameters.minimumValues[index];
            const max = this.parameters.maximumValues[index];
            this.parameters.values[index] = min <= 0 && max >= 0 ? 0 : min;
          }
        }
        for (const id of AXON_LIVE2D_WATERMARK_PARTS) {
          const partIndex = this.partIndex.get(id);
          if (partIndex !== undefined) this.model.parts.opacities[partIndex] = 0;
        }
      }
    }

    dispose() {
      try { removeEventListener('resize', this.boundResize); } catch (_) {}
      const gl = this.gl;
      if (gl) {
        for (const texture of this.textures) { try { if (texture) gl.deleteTexture(texture); } catch (_) {} }
        this.textures.length = 0;
        try { if (this.positionBuffer) gl.deleteBuffer(this.positionBuffer); } catch (_) {}
        try { if (this.uvBuffer) gl.deleteBuffer(this.uvBuffer); } catch (_) {}
        try { if (this.indexBuffer) gl.deleteBuffer(this.indexBuffer); } catch (_) {}
        this.releaseMaskTarget();
        try { if (this.program) gl.deleteProgram(this.program); } catch (_) {}
        try { if (this.maskProgram) gl.deleteProgram(this.maskProgram); } catch (_) {}
        try { const lose = gl.getExtension('WEBGL_lose_context'); if (lose) lose.loseContext(); } catch (_) {}
      }
      try { if (this.model && typeof this.model.release === 'function') this.model.release(); } catch (_) {}
      this.physicsActionUntil = 0;
      this.physicsOutputDeltas.clear();
      this.frameInputs.clear();
      this.overrides.clear();
    }

    bindGeometry(program, drawableIndex, options = null) {
      const gl = this.gl;
      const d = this.drawables;
      const opts = options || {};
      gl.useProgram(program);

      const positionAttribute = gl.getAttribLocation(program, 'aPosition');
      const uvAttribute = gl.getAttribLocation(program, 'aUv');
      gl.bindBuffer(gl.ARRAY_BUFFER, this.positionBuffer);
      gl.bufferData(gl.ARRAY_BUFFER, d.vertexPositions[drawableIndex], gl.DYNAMIC_DRAW);
      if (positionAttribute >= 0) {
        gl.enableVertexAttribArray(positionAttribute);
        gl.vertexAttribPointer(positionAttribute, 2, gl.FLOAT, false, 0, 0);
      }

      gl.bindBuffer(gl.ARRAY_BUFFER, this.uvBuffer);
      gl.bufferData(gl.ARRAY_BUFFER, d.vertexUvs[drawableIndex], gl.DYNAMIC_DRAW);
      if (uvAttribute >= 0) {
        gl.enableVertexAttribArray(uvAttribute);
        gl.vertexAttribPointer(uvAttribute, 2, gl.FLOAT, false, 0, 0);
      }

      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.indexBuffer);
      gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, d.indices[drawableIndex], gl.DYNAMIC_DRAW);

      const canvasUniform = gl.getUniformLocation(program, 'uCanvas');
      const viewportUniform = gl.getUniformLocation(program, 'uViewport');
      if (canvasUniform !== null) gl.uniform4f(
        canvasUniform,
        this.canvasInfo.CanvasOriginX * this.scale + this.offsetX,
        this.canvasInfo.CanvasOriginY * this.scale + this.offsetY,
        this.canvasInfo.PixelsPerUnit * this.scale,
        this.pixelWidth,
      );
      if (viewportUniform !== null) gl.uniform2f(viewportUniform, this.pixelWidth, this.pixelHeight);

      const opacityUniform = gl.getUniformLocation(program, 'uOpacity');
      if (opacityUniform !== null) gl.uniform1f(opacityUniform, this.drawableOpacity(drawableIndex) * Math.max(0, Math.min(1, Number(this.modelOpacity) || 0)));

      const multiplyUniform = gl.getUniformLocation(program, 'uMultiplyColor');
      if (multiplyUniform !== null) {
        const color = this.readDrawableColor(d.multiplyColors, drawableIndex, [1, 1, 1, 1]);
        gl.uniform4f(multiplyUniform, color[0], color[1], color[2], color[3]);
      }
      const screenUniform = gl.getUniformLocation(program, 'uScreenColor');
      if (screenUniform !== null) {
        const color = this.readDrawableColor(d.screenColors, drawableIndex, [0, 0, 0, 1]);
        gl.uniform4f(screenUniform, color[0], color[1], color[2], color[3]);
      }

      const useMaskUniform = gl.getUniformLocation(program, 'uUseMask');
      if (useMaskUniform !== null) gl.uniform1f(useMaskUniform, opts.useMask && this.maskTexture ? 1 : 0);
      const invertedUniform = gl.getUniformLocation(program, 'uMaskInverted');
      if (invertedUniform !== null) gl.uniform1f(invertedUniform, opts.inverted ? 1 : 0);
      const maskSampler = gl.getUniformLocation(program, 'uMaskTexture');
      if (maskSampler !== null && opts.useMask && this.maskTexture) {
        gl.activeTexture(gl.TEXTURE1);
        gl.bindTexture(gl.TEXTURE_2D, this.maskTexture);
        gl.uniform1i(maskSampler, 1);
      }

      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, opts.texture || this.drawableTexture(drawableIndex));
      const sampler = gl.getUniformLocation(program, 'uTexture');
      if (sampler !== null) gl.uniform1i(sampler, 0);
    }

    applyDrawableBlend(drawableIndex) {
      const gl = this.gl;
      const flags = this.drawables.constantFlags[drawableIndex];
      const utils = this.core && this.core.Utils ? this.core.Utils : {};
      // Cubism's premultiplied blend modes deliberately keep Add/Multiply from modifying the
      // destination alpha. gl.blendFunc() applies RGB factors to alpha too and can make a transparent
      // overlay become opaque black/white when a model uses many Add/Multiply ArtMeshes.
      if (typeof utils.hasBlendAdditiveBit === 'function' && utils.hasBlendAdditiveBit(flags)) {
        gl.blendFuncSeparate(gl.ONE, gl.ONE, gl.ZERO, gl.ONE);
      } else if (typeof utils.hasBlendMultiplicativeBit === 'function' && utils.hasBlendMultiplicativeBit(flags)) {
        gl.blendFuncSeparate(gl.DST_COLOR, gl.ONE_MINUS_SRC_ALPHA, gl.ZERO, gl.ONE);
      } else {
        gl.blendFuncSeparate(gl.ONE, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      }
    }

    draw(drawableIndex, mask, useMask = false, inverted = false) {
      const texture = this.drawableTexture(drawableIndex);
      if (!texture) return false;
      const count = Number(this.drawables.indexCounts && this.drawables.indexCounts[drawableIndex]) || 0;
      if (count <= 0) return false;
      const program = mask ? this.maskProgram : this.program;
      if (!mask) this.applyDrawableBlend(drawableIndex);
      this.bindGeometry(program, drawableIndex, { texture, useMask, inverted });
      this.gl.drawElements(this.gl.TRIANGLES, count, this.gl.UNSIGNED_SHORT, 0);
      return true;
    }

    render(deltaSeconds) {
      const gl = this.gl;
      const d = this.drawables;
      this.prepareParameters(deltaSeconds);
      this.model.update();
      this.lastMaskKey = '';

      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      gl.viewport(0, 0, this.pixelWidth, this.pixelHeight);
      gl.clearColor(0, 0, 0, 0);
      gl.clearStencil(0);
      gl.clear(gl.COLOR_BUFFER_BIT | gl.STENCIL_BUFFER_BIT);
      gl.disable(gl.DEPTH_TEST);
      gl.disable(gl.CULL_FACE);
      gl.enable(gl.BLEND);
      gl.blendEquation(gl.FUNC_ADD);
      gl.blendFuncSeparate(gl.ONE, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);

      const order = Array.from({ length: d.count }, (_, index) => index)
        .sort((a, b) => d.renderOrders[a] - d.renderOrders[b]);

      for (const index of order) {
        if (!this.core.Utils.hasIsVisibleBit(d.dynamicFlags[index])) continue;
        if (this.drawableOpacity(index) <= 0.00001) continue;
        if (!this.drawableTexture(index)) continue;

        const maskCount = Math.max(0, Number(d.maskCounts && d.maskCounts[index]) || 0);
        if (maskCount > 0) {
          const inverted = this.core.Utils.hasIsInvertedMaskBit(d.constantFlags[index]);
          if (this.buildAlphaMask(d.masks[index], maskCount)) {
            this.draw(index, false, true, inverted);
          } else {
            // Extremely constrained/buggy WebGL implementations can reject the auxiliary FBO.
            // Retain the previous stencil path as a safe fallback rather than dropping the model.
            gl.bindFramebuffer(gl.FRAMEBUFFER, null);
            gl.viewport(0, 0, this.pixelWidth, this.pixelHeight);
            gl.enable(gl.STENCIL_TEST);
            gl.clear(gl.STENCIL_BUFFER_BIT);
            gl.colorMask(false, false, false, false);
            gl.stencilMask(0xff);
            gl.stencilFunc(gl.ALWAYS, 1, 0xff);
            gl.stencilOp(gl.KEEP, gl.KEEP, gl.REPLACE);
            for (let maskIndex = 0; maskIndex < maskCount; maskIndex++) this.draw(d.masks[index][maskIndex], true);
            gl.colorMask(true, true, true, true);
            gl.stencilMask(0x00);
            gl.stencilFunc(inverted ? gl.NOTEQUAL : gl.EQUAL, 1, 0xff);
            gl.stencilOp(gl.KEEP, gl.KEEP, gl.KEEP);
            this.draw(index, false, false, false);
            gl.disable(gl.STENCIL_TEST);
            gl.stencilMask(0xff);
          }
        } else {
          this.draw(index, false, false, false);
        }
      }

      d.resetDynamicFlags();
    }
  }

  function loadImage(src) {
    return new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = () => resolve(image);
      image.onerror = () => reject(new Error(`texture load failed: ${src}`));
      image.src = src;
    });
  }

  function base64ToBytes(value) {
    const raw = atob(String(value || ''));
    if (!raw) return null;
    const bytes = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
    return bytes;
  }

  function loadFileArrayBuffer(src) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', src, true);
      xhr.responseType = 'arraybuffer';
      xhr.onload = () => {
        // file:// commonly reports status 0 on Android WebView.
        if ((xhr.status === 0 || (xhr.status >= 200 && xhr.status < 300)) && xhr.response) resolve(xhr.response);
        else reject(new Error(`moc load failed: HTTP ${xhr.status}`));
      };
      xhr.onerror = () => reject(new Error(`moc load failed: ${src}`));
      xhr.send();
    });
  }

  async function loadMocBytes() {
    const inline = String(CONFIG.mocBase64 || '');
    if (inline) {
      const bytes = base64ToBytes(inline);
      if (bytes && bytes.length) return bytes;
    }
    const uri = String(CONFIG.mocUri || '');
    if (!uri) throw new Error('moc data missing');
    const buffer = await loadFileArrayBuffer(uri);
    return new Uint8Array(buffer);
  }

  async function init() {
    state.runtimeError = '';
    try {
      if (IS_MVER) {
        initializeMverLayers();
        if (!MVER_USE_LIVE2D) {
          canvas.classList.add('hidden');
          state.ready = true;
          console.info('[AxonBongoCat] Mver sprite runtime ready');
          return;
        }
      }

      let core;
      for (let attempt = 0; attempt < 300; attempt++) {
        core = window.Live2DCubismCore;
        if (core && core.Moc && core.Model) break;
        await new Promise((resolve) => setTimeout(resolve, 16));
      }
      if (!core || !core.Moc) throw new Error('Cubism Core unavailable');
      try {
        const coreVersion = core.Version && core.Version.csmGetVersion ? core.Version.csmGetVersion() : 0;
        const latestMoc = core.Version && core.Version.csmGetLatestMocVersion ? core.Version.csmGetLatestMocVersion() : 0;
        console.info('[AxonBongoCat] Cubism compatibility', { coreVersion, latestMoc, modelMoc: Number(CONFIG.mocVersion || 0) });
      } catch (_) {}

      const bytes = await loadMocBytes();
      const textureUrls = Array.isArray(CONFIG.textures) ? CONFIG.textures : [];
      if (!textureUrls.length) throw new Error('texture list missing');

      let moc;
      try {
        moc = core.Moc.fromArrayBuffer(bytes.buffer);
      } catch (error) {
        const modelVersion = Number(CONFIG.mocVersion || 0);
        const hint = modelVersion >= 5 ? ' (MOC3 v5 requires a Cubism 5+ Core)' : '';
        throw new Error(`moc parse failed${hint}: ${String(error && error.message || error || '')}`);
      }
      if (!moc) throw new Error('moc parse failed');
      const model = core.Model.fromMoc(moc);
      if (!model) throw new Error('model init failed');

      // Keep the global renderer unpublished until every atlas has been uploaded. The animation
      // loop is already alive while init() awaits; publishing a half-initialized renderer can make
      // it draw with missing texture slots on large multi-atlas models and fail nondeterministically.
      const createdRenderer = new CoreRenderer(core, model, []);
      await createdRenderer.loadTexturesSequentially(textureUrls);
      renderer = createdRenderer;
      applySavedParameterValues();
      applyLive2DWatermarkVisibility(AXON_LIVE2D_HIDE_WATERMARK);
      // Match Mver 0.1.6 compositor semantics: l2d_horizontal_flip is not a visual canvas
      // mirror. The desk/background and full-frame hand overlays are already authored in final
      // coordinates, so only keep the model in its native orientation.
      canvas.style.transform = '';
      if (IS_MVER && MVER_SOURCE_L2D_HORIZONTAL_FLIP) {
        console.info('[AxonBongoCat] legacy l2d_horizontal_flip preserved as metadata; visual mirror intentionally ignored');
      }
      if (IS_MVER && MVER_MOUSE_FORCE_MOVE) {
        state.pointerActive = true;
        applyPointerOverrides(state.cursorX, state.cursorY);
      }
      syncHandOverrides();
      state.mouseVisualButtons = state.mouseButtons;
      fallback.classList.add('hidden');
      state.staticFallback = false;
      state.ready = true;
      console.info('[AxonBongoCat] runtime ready', JSON.stringify(window.AxonBongoCat.debugState()));

      scheduleAnimation();
    } catch (error) {
      state.runtimeError = String(error && (error.stack || error.message) || error || 'unknown runtime error');
      console.error('BongoCat source model init failed', error);
      const hasImportedCover = !IS_MVER && Boolean(String(CONFIG.cover || ''));
      if (hasImportedCover) {
        // Keep the imported skin visible even when a particular GPU/WebView cannot initialize its
        // Live2D renderer. Input overlays still work, so do not bounce all the way back to the
        // built-in white cat and make a successful import look like it was ignored.
        canvas.classList.add('hidden');
        fallback.classList.remove('hidden');
        state.staticFallback = true;
        state.ready = true;
        console.warn('[AxonBongoCat] using imported static cover fallback');
      } else {
        fallback.classList.remove('hidden');
      }
    }
  }

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) pauseRuntime();
    else resumeRuntime();
  }, { passive: true });
  addEventListener('pagehide', pauseRuntime, { passive: true });
  addEventListener('pageshow', resumeRuntime, { passive: true });

  init();
  scheduleAnimation();
})();
