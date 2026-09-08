/** Client browser per il bridge locale Wacom STU. */
window.WacomStu = (() => {
  const bridgeUrl = 'http://127.0.0.1:8765';

  async function request(path, options) {
    const response = await fetch(bridgeUrl + path, {
      cache: 'no-store',
      ...options
    });
    let data;
    try {
      data = await response.json();
    } catch (_) {
      throw new Error('Risposta non valida dal bridge Wacom');
    }
    if (!response.ok || !data.ok) {
      const error = new Error(data.error || 'Operazione Wacom non riuscita');
      error.cancelled = Boolean(data.cancelled);
      throw error;
    }
    return data;
  }

  async function health() {
    const result = await request('/api/health');
    return {...result, ready: result.devices > 0};
  }

  function capture() {
    return request('/api/capture', {method: 'POST'});
  }

  function toText(signature) {
    const metadata = [
      '# format=wacom-stu-points-v1',
      '# capturedAt=' + signature.capturedAt,
      '# tabletMaxX=' + signature.tabletMaxX,
      '# tabletMaxY=' + signature.tabletMaxY,
      '# screenWidth=' + signature.screenWidth,
      '# screenHeight=' + signature.screenHeight
    ];
    const columns = 'index\tx\ty\tpressure\tpenDown\ttimeCount\tsequence';
    const rows = signature.points.map(point => [
      point.index,
      point.x,
      point.y,
      point.pressure,
      point.penDown,
      point.timeCount ?? '',
      point.sequence ?? ''
    ].join('\t'));
    return [...metadata, columns, ...rows].join('\r\n');
  }

  function draw(canvas, signature) {
    const context = canvas.getContext('2d');
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.strokeStyle = '#172554';
    context.lineWidth = 3;
    context.lineCap = 'round';
    context.lineJoin = 'round';
    let previous = null;
    for (const point of signature.points) {
      const current = {
        x: point.x * canvas.width / signature.tabletMaxX,
        y: point.y * canvas.height / signature.tabletMaxY,
        down: point.penDown
      };
      if (current.down && previous?.down) {
        context.beginPath();
        context.moveTo(previous.x, previous.y);
        context.lineTo(current.x, current.y);
        context.stroke();
      }
      previous = current;
    }
  }

  return Object.freeze({health, capture, toText, draw});
})();
