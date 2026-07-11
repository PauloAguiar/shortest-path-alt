// Copies the plugin's real map + transport data into public/ so the app can fetch it.
import { cpSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const resources = join(here, '..', '..', '..', 'src', 'main', 'resources');
const pub = join(here, '..', 'public');

mkdirSync(join(pub, 'transports'), { recursive: true });
cpSync(join(resources, 'collision-map.zip'), join(pub, 'collision-map.zip'));
const files = readdirSync(join(resources, 'transports')).filter(f => f.endsWith('.tsv'));
for (const f of files) {
	cpSync(join(resources, 'transports', f), join(pub, 'transports', f));
}
writeFileSync(join(pub, 'transports', 'manifest.json'), JSON.stringify(files, null, 1));
console.log(`assets: collision-map.zip + ${files.length} transport files -> public/`);
