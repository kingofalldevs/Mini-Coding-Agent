const log = document.getElementById('log');
const form = document.getElementById('composer');
const input = document.getElementById('input');
const send = document.getElementById('send');
const reset = document.getElementById('reset');

let conversationId = null;
let busy = false;

function addMessage(role, text) {
    const el = document.createElement('div');
    el.className = 'msg ' + role;
    if (role === 'agent') {
        el.innerHTML = renderMarkdown(text);
    } else {
        el.textContent = text;
    }
    log.appendChild(el);
    log.scrollTop = log.scrollHeight;
    return el;
}

function showHint() {
    const el = document.createElement('p');
    el.className = 'hint';
    el.id = 'hint';
    el.textContent = 'No conversation yet. Ask something to get started.';
    log.appendChild(el);
}

showHint();

function clearHint() {
    const hint = document.getElementById('hint');
    if (hint) hint.remove();
}

/* ---------------------------------------------------------------------------
 * Minimal Markdown renderer.
 * Input is escaped first, so model output can never inject HTML.
 * Supports: fenced code, headings, hr, blockquote, ordered/unordered lists,
 * tables, paragraphs, bold/italic/strikethrough, inline code and links.
 * ------------------------------------------------------------------------- */

function escapeHtml(s) {
    return s
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function renderInline(text) {
    let out = escapeHtml(text);
    // Inline code first so its contents are not further transformed.
    const codes = [];
    out = out.replace(/`([^`]+)`/g, (m, c) => {
        codes.push('<code>' + c + '</code>');
        return '\u0000CODE' + (codes.length - 1) + '\u0000';
    });
    out = out.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, '<em>[image: $1]</em>');
    out = out.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g,
        '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
    out = out.replace(/\*\*\*([^*]+)\*\*\*/g, '<strong><em>$1</em></strong>');
    out = out.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    out = out.replace(/__([^_]+)__/g, '<strong>$1</strong>');
    out = out.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>');
    out = out.replace(/(^|[^_])_([^_\n]+)_/g, '$1<em>$2</em>');
    out = out.replace(/~~([^~]+)~~/g, '<del>$1</del>');
    out = out.replace(/\u0000CODE(\d+)\u0000/g, (m, i) => codes[Number(i)]);
    return out;
}

function isTableSeparator(line) {
    return /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/.test(line);
}

function splitRow(line) {
    let row = line.trim();
    if (row.startsWith('|')) row = row.slice(1);
    if (row.endsWith('|')) row = row.slice(0, -1);
    return row.split('|').map((c) => c.trim());
}

function renderMarkdown(src) {
    if (!src) return '';
    const lines = src.replace(/\r\n?/g, '\n').split('\n');
    const html = [];
    let i = 0;

    const listItem = (line) => /^\s*([-*+]|\d+[.)])\s+/.test(line);
    const orderedItem = (line) => /^\s*\d+[.)]\s+/.test(line);

    while (i < lines.length) {
        const line = lines[i];

        // Fenced code block
        const fence = line.match(/^\s*```(\S*)\s*$/);
        if (fence) {
            const lang = fence[1];
            const body = [];
            i++;
            while (i < lines.length && !/^\s*```\s*$/.test(lines[i])) {
                body.push(lines[i]);
                i++;
            }
            i++; // skip closing fence
            const cls = lang ? ' class="language-' + escapeHtml(lang) + '"' : '';
            html.push('<pre><code' + cls + '>' + escapeHtml(body.join('\n')) + '</code></pre>');
            continue;
        }

        // Blank line
        if (/^\s*$/.test(line)) {
            i++;
            continue;
        }

        // Horizontal rule
        if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) {
            html.push('<hr>');
            i++;
            continue;
        }

        // Heading
        const heading = line.match(/^\s*(#{1,6})\s+(.*)$/);
        if (heading) {
            const level = heading[1].length;
            html.push('<h' + level + '>' + renderInline(heading[2].trim()) + '</h' + level + '>');
            i++;
            continue;
        }

        // Table
        if (line.includes('|') && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
            const header = splitRow(line);
            i += 2;
            const body = [];
            while (i < lines.length && lines[i].includes('|') && !/^\s*$/.test(lines[i])) {
                body.push(splitRow(lines[i]));
                i++;
            }
            let t = '<div class="table-wrap"><table><thead><tr>';
            for (const cell of header) t += '<th>' + renderInline(cell) + '</th>';
            t += '</tr></thead><tbody>';
            for (const row of body) {
                t += '<tr>';
                for (let c = 0; c < header.length; c++) {
                    t += '<td>' + renderInline(row[c] === undefined ? '' : row[c]) + '</td>';
                }
                t += '</tr>';
            }
            t += '</tbody></table></div>';
            html.push(t);
            continue;
        }

        // Blockquote
        if (/^\s*>\s?/.test(line)) {
            const body = [];
            while (i < lines.length && /^\s*>\s?/.test(lines[i])) {
                body.push(lines[i].replace(/^\s*>\s?/, ''));
                i++;
            }
            html.push('<blockquote>' + renderMarkdown(body.join('\n')) + '</blockquote>');
            continue;
        }

        // Lists
        if (listItem(line)) {
            const ordered = orderedItem(line);
            const tag = ordered ? 'ol' : 'ul';
            let out = '<' + tag + '>';
            while (i < lines.length && listItem(lines[i])) {
                const content = lines[i].replace(/^\s*([-*+]|\d+[.)])\s+/, '');
                out += '<li>' + renderInline(content) + '</li>';
                i++;
            }
            out += '</' + tag + '>';
            html.push(out);
            continue;
        }

        // Paragraph: gather until a blank line or a new block starts.
        const para = [];
        while (
            i < lines.length &&
            !/^\s*$/.test(lines[i]) &&
            !/^\s*```/.test(lines[i]) &&
            !/^\s*#{1,6}\s+/.test(lines[i]) &&
            !/^\s*>\s?/.test(lines[i]) &&
            !listItem(lines[i]) &&
            !/^\s*([-*_])(\s*\1){2,}\s*$/.test(lines[i])
        ) {
            para.push(lines[i]);
            i++;
        }
        if (para.length) {
            html.push('<p>' + renderInline(para.join('\n')).replace(/\n/g, '<br>') + '</p>');
        }
    }

    return html.join('\n');
}

/* ---------------------------------------------------------------------------
 * Event wiring
 * ------------------------------------------------------------------------- */

reset.addEventListener('click', () => {
    conversationId = null;
    log.innerHTML = '';
    showHint();
    input.focus();
});

// Grow the textarea with its content, up to the CSS max-height.
input.addEventListener('input', () => {
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 180) + 'px';
});

input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        form.requestSubmit();
    }
});

/* Parse an SSE stream and hand each event's raw data to onData.
 *
 * Per the SSE spec, multiple `data:` lines inside one event are joined with
 * "\n". Spring's SSE encoder also turns newlines inside a value into extra
 * `data:` lines, and it drops values that are whitespace-only -- which is how
 * a chunk consisting of a single space between two words used to disappear.
 * The server therefore sends each chunk as JSON: {"t":"..."} for text (so any
 * whitespace is escaped and survives the wire) and {"nl":n} for runs of line
 * breaks. Frames are separated by "\n\n" or "\r\n\r\n".
 *
 * Note: a "\r\n" can be split across two network reads, leaving a trailing
 * "\r" in the buffer and the "\n" at the head of the next chunk. The buffer is
 * therefore normalised only up to its last complete line, and a lone trailing
 * "\r" is held back until its partner arrives.
 */
async function consumeSse(response, onData) {
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    const drain = (frame) => {
        const dataLines = [];
        for (const line of frame.split('\n')) {
            if (!line.startsWith('data:')) continue;
            // Spec: strip a single leading space after "data:".
            dataLines.push(line.slice(5).replace(/^ /, ''));
        }
        if (dataLines.length) onData(dataLines.join('\n'));
    };

    const normalise = (chunk) => {
        let s = chunk.replace(/\r\n/g, '\n');
        // Hold back a trailing "\r" that may be the first half of "\r\n".
        if (s.endsWith('\r')) s = s.slice(0, -1);
        return s;
    };

    while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += normalise(decoder.decode(value, { stream: true }));

        const frames = buffer.split('\n\n');
        buffer = frames.pop();

        for (const frame of frames) drain(frame);
    }

    // Flush any final frame that arrived without a trailing blank line.
    buffer = normalise(buffer.replace(/\r$/, '\n'));
    if (buffer.trim()) drain(buffer);
}

form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const message = input.value.trim();
    if (!message || busy) return;

    busy = true;
    send.disabled = true;
    clearHint();

    addMessage('user', message);
    input.value = '';
    input.style.height = 'auto';

    const agentEl = addMessage('agent', '');
    agentEl.classList.add('streaming');
    let raw = '';

    const paint = () => {
        agentEl.innerHTML = renderMarkdown(raw);
        log.scrollTop = log.scrollHeight;
    };

    try {
        const response = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message, conversationId })
        });

        if (!response.ok) {
            throw new Error('HTTP ' + response.status + ' ' + response.statusText);
        }

        await consumeSse(response, (data) => {
            let frame;
            try {
                frame = JSON.parse(data);
            } catch (err) {
                // Fall back to raw text if the server ever sends non-JSON.
                raw += data;
                paint();
                return;
            }
            if (frame.cid) {
                conversationId = String(frame.cid).trim();
                return;
            }
            if (typeof frame.nl === 'number' && frame.nl > 0) {
                raw += '\n'.repeat(frame.nl);
                paint();
                return;
            }
            if (typeof frame.t === 'string') {
                raw += frame.t;
                paint();
            }
        });

        if (!raw.trim()) {
            agentEl.textContent = '(no response)';
        }
    } catch (err) {
        agentEl.classList.add('error');
        agentEl.textContent = 'Request failed: ' + err.message;
    } finally {
        agentEl.classList.remove('streaming');
        busy = false;
        send.disabled = false;
        input.focus();
    }
});
