/* ==========================================================================
   Mini Coding Agent — frontend
   Streams SSE frames from /api/chat and renders incremental markdown.
   ========================================================================== */

const logEl = document.getElementById('log');
const form = document.getElementById('composer');
const input = document.getElementById('input');
const sendBtn = document.getElementById('send');
const resetBtn = document.getElementById('reset');
const statusDot = document.getElementById('status');
const jumpWrap = document.getElementById('jump');
const jumpBtn = document.getElementById('jump-btn');

let conversationId = null;
let busy = false;
/** True while the user is parked at the bottom; disables autoscroll when they scroll up to read. */
let pinned = true;

/* ==========================================================================
   Small helpers
   ========================================================================== */

function setBusy(value) {
    busy = value;
    sendBtn.disabled = value;
    statusDot.dataset.state = value ? 'busy' : 'idle';
    statusDot.title = value ? 'Thinking…' : 'Idle';
}

function nearBottom() {
    return logEl.scrollHeight - logEl.scrollTop - logEl.clientHeight < 80;
}

function scrollToBottom(force) {
    if (force || pinned) logEl.scrollTop = logEl.scrollHeight;
}

/** Autoscroll only when pinned, and surface a jump button when the user has scrolled away. */
logEl.addEventListener('scroll', () => {
    pinned = nearBottom();
    jumpWrap.hidden = pinned;
});

jumpBtn.addEventListener('click', () => {
    pinned = true;
    jumpWrap.hidden = true;
    scrollToBottom(true);
});

/* ==========================================================================
   Empty state
   ========================================================================== */

const STARTERS = [
    'List the files in the workspace',
    'Create a notes file and write a summary',
    'Read README.md and explain it'
];

function showEmptyState() {
    const el = document.createElement('div');
    el.className = 'empty';
    el.id = 'empty';

    const title = document.createElement('p');
    title.className = 'empty-title';
    title.textContent = 'What should I do with your files?';

    const sub = document.createElement('p');
    sub.className = 'empty-sub';
    sub.textContent = 'Read, create, move or delete — the agent does the work and logs every step.';

    const starters = document.createElement('div');
    starters.className = 'starters';
    for (const text of STARTERS) {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'starter';
        b.textContent = text;
        b.addEventListener('click', () => {
            input.value = text;
            input.focus();
            autosize();
        });
        starters.appendChild(b);
    }

    el.append(title, sub, starters);
    logEl.appendChild(el);
}

function clearEmptyState() {
    document.getElementById('empty')?.remove();
}

/* ==========================================================================
   Message rendering
   ========================================================================== */

const AGENT_MARK = 'A';

function addTurn(role, text) {
    const turn = document.createElement('div');
    turn.className = 'turn ' + role;

    const bubble = document.createElement('div');
    bubble.className = 'bubble';

    if (role === 'agent') {
        const avatar = document.createElement('div');
        avatar.className = 'avatar';
        avatar.textContent = AGENT_MARK;
        turn.appendChild(avatar);
        bubble.innerHTML = renderMarkdown(text);
    } else {
        bubble.textContent = text;
    }

    turn.appendChild(bubble);
    logEl.appendChild(turn);
    scrollToBottom();

    return bubble;
}

/* ==========================================================================
   Minimal Markdown renderer
   Input is escaped first, so model output can never inject HTML.
   Supports: fenced code, headings, hr, blockquote, ordered/unordered lists,
   tables, paragraphs, bold/italic/strikethrough, inline code and links.
   ========================================================================== */

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

/* ==========================================================================
   SSE parsing
   Spring's encoder drops whitespace-only values, so each chunk arrives as
   JSON: {"t":"..."} for text (whitespace survives escaping), {"nl":n} for runs
   of line breaks, and {"cid":"..."} for the conversation id. Frames are
   separated by a blank line. A "\r\n" can straddle two reads, so a lone
   trailing "\r" is held back until its partner arrives.
   ========================================================================== */

async function consumeSse(response, onData) {
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    const drain = (frame) => {
        const dataLines = [];
        for (const line of frame.split('\n')) {
            if (!line.startsWith('data:')) continue;
            dataLines.push(line.slice(5).replace(/^ /, ''));
        }
        if (dataLines.length) onData(dataLines.join('\n'));
    };

    const normalise = (chunk) => {
        let s = chunk.replace(/\r\n/g, '\n');
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

    buffer = normalise(buffer.replace(/\r$/, '\n'));
    if (buffer.trim()) drain(buffer);
}

/* ==========================================================================
   Composer behaviour
   ========================================================================== */

function autosize() {
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 180) + 'px';
}

input.addEventListener('input', autosize);

input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        form.requestSubmit();
    }
});

resetBtn.addEventListener('click', () => {
    conversationId = null;
    logEl.innerHTML = '';
    pinned = true;
    jumpWrap.hidden = true;
    showEmptyState();
    input.focus();
});

form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const message = input.value.trim();
    if (!message || busy) return;

    setBusy(true);
    pinned = true;
    clearEmptyState();

    addTurn('user', message);
    input.value = '';
    autosize();

    const bubble = addTurn('agent', '');
    bubble.classList.add('streaming');
    let raw = '';

    const paint = () => {
        bubble.innerHTML = renderMarkdown(raw);
        scrollToBottom();
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
            } catch {
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
            bubble.textContent = '(no response)';
        }
    } catch (err) {
        bubble.classList.add('error');
        bubble.textContent = 'Request failed: ' + err.message;
    } finally {
        bubble.classList.remove('streaming');
        setBusy(false);
        input.focus();
    }
});

/* ==========================================================================
   Boot
   ========================================================================== */

showEmptyState();
input.focus();
