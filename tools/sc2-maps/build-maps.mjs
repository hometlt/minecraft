// Skirmish maps for the mod (owner 22.10): every map of tools/sc2-maps/maps.json is downloaded from the Battle.net
// public depot by its publication header, converted by Modkit to a native Minecraft Java world and written, with the
// manifest the mod reads (starts, mineral groups, gas roots), to <out>/assets/starmine/skirmish/. Uses only Modkit's
// owners: the depot API (download by hash), the Host map preview and UVP packer, and the Minecraft extension's
// native encoder through the Modkit MCP server. Nothing is written into the SC2 installation.
//
// node tools/sc2-maps/build-maps.mjs --out <generated resources dir> [--modkit <workspace>] [--sc2 <install>]
//     [--work <scratch>] [--bnet-cache <Battle.net cache>] [--only <map id>]
// Each step is skipped when its output exists for the same inputs (<work>/<id>/state.json), so a mod build that
// runs it again is quick. Steps: header -> archive (.SC2Map) -> UVP -> world zip -> manifest.
import {createHash} from 'node:crypto';
import {copyFileSync,existsSync,mkdirSync,readFileSync,readdirSync,statSync,writeFileSync} from 'node:fs';
import {dirname,join,resolve} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';

const option = name => {const i=process.argv.indexOf(name);return i<0?undefined:process.argv[i+1]};
const project=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
const workspace=resolve(option('--modkit')??join(project,'..'));
const core=join(workspace,'modkit-core'), blizzard=join(workspace,'modkit-extensions-blizzard'), minecraft=join(workspace,'modkit-extensions-minecraft');
const outRoot=option('--out');if(!outRoot)throw Error('--out must name the generated resources directory');
const skirmish=join(resolve(outRoot),'assets/starmine/skirmish');
const work=resolve(option('--work')??'C:/MKTEMP/starmine-maps');
const game=option('--sc2')??process.env.MODKITX_SC2_ROOT??'F:/GAMES/Blizzard/StarCraft II';
const bnetCache=option('--bnet-cache')??'C:/ProgramData/Blizzard Entertainment/Battle.net/Cache';
const only=option('--only');
const url=path=>pathToFileURL(path).href;
const sha256=bytes=>createHash('sha256').update(bytes).digest('hex');
const log=(event,data={})=>console.log(JSON.stringify({time:new Date().toISOString(),event,...data}));
const catalogue=JSON.parse(readFileSync(join(project,'tools/sc2-maps/maps.json'),'utf8'));
const content=join(blizzard,'starcraft2/dist/modules/game/battlenet/content-sources');
mkdirSync(skirmish,{recursive:true});mkdirSync(work,{recursive:true});

/** The publication's header handle: the catalogue's, else the local Battle.net cache's header with its id and version. */
async function headerHandle(map){
	if(map.bnet.headerHandle)return map.bnet.headerHandle;
	const {decodeCacheHeader}=await import(url(join(content,'cache-header.js')));
	const walk=dir=>readdirSync(dir).flatMap(name=>{const path=join(dir,name);return statSync(path).isDirectory()?walk(path):path.endsWith('.s2mh')?[path]:[]});
	for(const file of existsSync(bnetCache)?walk(bnetCache):[]){
		try{
			const ident=decodeCacheHeader(readFileSync(file)).value.get(0n).value.get(0n).value;
			if(Number(ident.get(0n).value)!==map.bnet.id||Number(ident.get(1n).value)!==map.bnet.version)continue;
			const region=Buffer.alloc(4);region.write(map.bnet.region.toUpperCase(),2,'latin1');
			return Buffer.from('s2mh','latin1').toString('hex')+region.toString('hex')+file.slice(-69,-5);
		}catch{}
	}
	throw Error(`${map.id}: no header handle for ${map.bnet.region} #${map.bnet.id} v${map.bnet.version} (name it in maps.json or open the map once in the game)`);
}

/** The map archive from the depot (or the game's cache), checked against its SHA-256. */
async function archive(map,dir){
	const {createPublicDepotApi}=await import(url(join(content,'public-depot.js')));
	const depot=createPublicDepotApi({cacheDirectory:existsSync(bnetCache)?bnetCache:undefined});
	const signal=AbortSignal.timeout(600_000);
	const publication={id:map.bnet.id,version:map.bnet.version,name:map.name,isMod:false,headerHandle:await headerHandle(map)};
	const found=await depot.resolveArchive({region:map.bnet.region,publication,signal});
	const path=join(dir,`${map.name}.SC2Map`);
	if(existsSync(path)&&sha256(readFileSync(path))===found.sha256)return {path,sha256:found.sha256};
	const chunks=[];for await(const chunk of depot.downloadArchive({region:map.bnet.region,archive:found,signal}))chunks.push(chunk);
	const bytes=Buffer.concat(chunks);if(sha256(bytes)!==found.sha256)throw Error(`${map.id}: depot archive SHA-256 mismatch`);
	writeFileSync(path,bytes);log('downloaded',{map:map.id,bytes:bytes.length,sha256:found.sha256});
	return {path,sha256:found.sha256};
}

/** The map's world as UVP through the Host map preview, as `modkit subject.convert --target uvp` does. */
async function uvp(map,source,dir){
	const lib=path=>import(url(join(core,path)));
	const {createModkit,createContentSourceMapPreviewResourcePlanResolver,createNodeMapPreviewResourcePlanResolver}=await lib('packages/host/dist/node/index.js');
	const {coordinateMapPreviewAssetSession}=await lib('packages/host/dist/maps/index.js');
	const {loadContentSources}=await lib('packages/vfs/dist/content-sources-node.js');
	const {materializeWorldToUvpMap,packUvpArchive}=await lib('packages/uv/dist/world/index.js');
	const config=join(dir,'sc2-sources.json');
	writeFileSync(config,JSON.stringify({module:join(content,'local-content-sources.js'),options:{installationDirectory:game,stateDirectory:join(dir,'state')}}));
	const contentSources=await loadContentSources(config,dir);
	const host=await createModkit({extensions:[],userExtensions:[join(core,'extensions/images'),join(core,'extensions/models'),join(blizzard,'starcraft2'),join(blizzard,'mpq'),join(blizzard,'models'),join(blizzard,'casc')],
		roots:[game,dir],intent:'full',subjectCatalog:{contentSources:async()=>contentSources}});
	try{
		const opened=await host.open(source);
		const preview=await coordinateMapPreviewAssetSession({services:host.services,grant:{id:'starmine-maps',classes:['read-source','filesystem-read','process-launch']},
			budgets:{maxInputBytes:1n<<32n,maxDepth:256,maxNodes:4_000_000,maxScalarBytes:268435456,maxDiagnostics:10000,deadlineMs:600000},original:opened.session,
			maxCandidates:64,maxSubjectEntries:4096,maxSubjectPages:64,maxRoots:64,maxDepth:16,
			catalogEnrichment:{locales:['enUS'],limits:{maxRequirements:1024,maxDocumentBytes:16777216,maxTextBytes:268435456,maxEntities:100000,maxDepth:128,maxPageSize:1000}},
			limits:{maxRequirements:32,maxDocumentBytes:67108864,maxTotalBytes:134217728,maxObjects:100000,maxRegions:10000,maxTerrainCells:100000,maxTerrainSamples:100000,maxDepth:64,maxPageSize:4096,maxDiagnostics:1000},
			resourcePlanResolver:createContentSourceMapPreviewResourcePlanResolver({contentSources:async()=>contentSources,
				fallback:createNodeMapPreviewResourcePlanResolver({subjects:host.services,installationDiscoveryGrant:{id:'discovery',classes:['filesystem-read','process-launch']}})})});
		if(preview.state!=='opened')throw Error(`${map.id}: no world preview: ${JSON.stringify(preview.diagnostics?.slice(0,5))}`);
		const lease=await preview.session.dataSource('common.view/world-scene-source',1);
		const packed=await materializeWorldToUvpMap(lease.value,{maxPageItems:4096,maxTerrainItems:4096*4096,maxFacetItems:4096*1024,maxOutputBytes:256*1024*1024,
			maxTextureBytes:64<<20,maxMediaBytes:1<<30,maxModelBytes:1<<30,maxModels:4096,packageId:'starmine.local/'+map.id,packageVersion:'1.0.0'});
		if(packed.state==='refused')throw Error(`${map.id}: UVP refused: ${JSON.stringify(packed.refusals??packed).slice(0,2000)}`);
		const bytes=packUvpArchive(packed.entries,{maxNodes:1_000_000,maxInputBytes:BigInt(4*1024**3-1)});
		const path=join(dir,map.id+'.uvp');writeFileSync(path,bytes);log('uvp',{map:map.id,bytes:bytes.length});
		return path;
	}finally{await Promise.race([host.close(),new Promise(done=>setTimeout(done,2000))]);}
}

/** The Modkit MCP server with the Minecraft extension; `use` gets a tool caller and a retained-output reader. */
async function withMcp(dir,use){
	const mcp=join(core,'apps/mcp');
	const {Client}=await import(url(join(mcp,'node_modules/@modelcontextprotocol/client/dist/index.mjs')));
	const {StdioClientTransport}=await import(url(join(mcp,'node_modules/@modelcontextprotocol/client/dist/stdio.mjs')));
	const sources=join(dir,'minecraft-sources.json');
	writeFileSync(sources,JSON.stringify({module:join(minecraft,'minecraft/dist/modules/game/content-sources/src/content-sources.js'),extension:'mojang/minecraft',options:{}}));
	const args=['--max-old-space-size=20000',join(mcp,'dist/main.js'),'--root',dir,
		...['images','models','archives','video'].flatMap(name=>['--extension',join(core,'extensions',name)]),
		'--trust-extensions','--output-artifacts','--no-shared-mod-server','--extension',join(minecraft,'minecraft'),
		'--content-source-config',sources,'--content-source-storage',join(dir,'minecraft-storage')];
	const transport=new StdioClientTransport({command:process.execPath,args,stderr:'pipe',cwd:mcp});
	const client=new Client({name:'starmine-maps',version:'1'});
	await client.connect(transport,{timeout:1_800_000});
	const call=async(name,input)=>{const r=await client.callTool({name,arguments:input},{timeout:1_800_000});if(r.isError)throw Error(name+': '+JSON.stringify(r.structuredContent??r).slice(0,2000));return r.structuredContent;};
	const save=async envelope=>{
		const artifact=envelope.data.outputArtifact;if(!artifact)throw Error('no retained output');
		const parts=[];for(let uri=artifact.firstChunkUri;uri;){const r=await client.readResource({uri});parts.push(Buffer.from(r.contents[0].blob,'base64'));uri=r._meta?.nextUri;}
		const bytes=Buffer.concat(parts);if(bytes.length!==artifact.byteLength||sha256(bytes)!==artifact.sha256)throw Error('retained output integrity');
		await call('modkit.release_output',{id:artifact.id});return bytes;
	};
	try{return await use(call,save);}finally{await client.close();}
}

/** The world zip from the UVP (the Minecraft extension's native terrain encoder), its conversion report and starts. */
async function world(map,uvpPath,dir){
	return withMcp(dir,async(call,save)=>{
		const handle=(await call('modkit.open_subject',{path:uvpPath})).data.handle;
		try{
			// the authored start locations, in the UVW frame
			const info=await call('modkit.world_scene',{handle,view:'info'});
			const starts=[];
			for(const facet of info.data.facets.filter(f=>f.facet==='objects'&&f.state==='available')){
				let cursor;do{
					const page=await call('modkit.world_scene',{handle,view:'page',facet:'objects',layerId:facet.layerId,limit:50,...(cursor?{cursor}:{})});
					for(const item of page.data.items)if(/^StartLoc/u.test(item.typeId??''))starts.push(item.uvwPosition??item.position);
					cursor=page.nextCursor??undefined;
				}while(cursor);
			}
			const name=`${map.id}-starmine`;
			const zip=await save(await call('modkit.invoke_readonly',{handle,operationId:'map.export-native',
				input:{schemaVersion:1,formatId:'minecraft-java-terrain-world',options:{name,...map.export}},retainOutput:true}));
			const zipPath=join(dir,name+'.zip');writeFileSync(zipPath,zip);
			const opened=(await call('modkit.open_subject',{path:zipPath})).data.handle;
			try{
				const report=JSON.parse((await save(await call('modkit.invoke_readonly',{handle:opened,operationId:'container.extract',input:{entryId:name+'/modkit-conversion.json'},retainOutput:true}))).toString('utf8'));
				return {zip,name,report,starts};
			}finally{await call('modkit.close_subject',{handle:opened});}
		}finally{await call('modkit.close_subject',{handle});}
	});
}

/** What the mod needs besides the world: starts (blocks), mineral fields (cores by field, sorted) and gas roots. */
function manifest(map,built,archiveSha){
	const frame=built.report.sourceFrame;
	const block=p=>({x:Math.floor((p.x-frame.x0)*frame.scale),z:Math.floor((p.z-frame.z0)*frame.scale)});
	const placed=built.report.objectStructures.placements.filter(p=>p.status==='placed');
	const minerals=placed.filter(p=>p.structure.includes('mineral')).map(p=>({x:p.x,y:p.y,z:p.z}));
	// a field: crystals within 12 blocks of one another (single linkage), sorted as the mod sets their amounts
	const fields=[],seen=new Set();
	for(const start of minerals){
		if(seen.has(start))continue;const field=[start];seen.add(start);
		for(let i=0;i<field.length;i++)for(const other of minerals)if(!seen.has(other)&&Math.hypot(other.x-field[i].x,other.z-field[i].z)<=12){seen.add(other);field.push(other);}
		fields.push(field.sort((a,b)=>a.x-b.x||a.z-b.z));
	}
	fields.sort((a,b)=>a[0].x-b[0].x||a[0].z-b[0].z);
	if(built.starts.length<2)throw Error(`${map.id}: ${built.starts.length} start locations`);
	return {id:map.id,name:map.name,world:built.name,zipSha256:sha256(built.zip),starts:built.starts.map(block),
		source:{region:map.bnet.region,id:map.bnet.id,version:map.bnet.version,archiveSha256:archiveSha},
		frame:{x0:frame.x0,z0:frame.z0,scale:frame.scale},spawn:built.report.spawn,dimensions:built.report.dimensions,
		mineralGroups:fields,gasRoots:placed.filter(p=>p.structure==='gas_geyser').map(p=>({x:p.x,y:p.y,z:p.z}))};
}

const index=[];
for(const map of catalogue.maps){
	if(only&&map.id!==only)continue;
	const dir=join(work,map.id);mkdirSync(dir,{recursive:true});
	const statePath=join(dir,'state.json'),state=existsSync(statePath)?JSON.parse(readFileSync(statePath,'utf8')):{};
	const exportKey=sha256(JSON.stringify(map.export));
	const out=join(skirmish,map.id+'.zip'),manifestPath=join(skirmish,map.id+'.json');
	const source=await archive(map,dir);
	if(state.archive!==source.sha256||!existsSync(join(dir,map.id+'.uvp'))){await uvp(map,source.path,dir);state.archive=source.sha256;state.world=undefined;writeFileSync(statePath,JSON.stringify(state,null,1));}
	// the built world and manifest are kept in the work folder; an output folder of another build gets copies
	const keptZip=join(dir,map.id+'.world.zip'),keptManifest=join(dir,map.id+'.manifest.json');
	if(state.world!==exportKey||!existsSync(keptZip)||!existsSync(keptManifest)){
		const built=await world(map,join(dir,map.id+'.uvp'),dir);
		const data=manifest(map,built,source.sha256);
		writeFileSync(keptZip,built.zip);writeFileSync(keptManifest,JSON.stringify(data,null,1));
		state.world=exportKey;writeFileSync(statePath,JSON.stringify(state,null,1));
		log('world',{map:map.id,bytes:built.zip.length,starts:data.starts,fields:data.mineralGroups.length,geysers:data.gasRoots.length});
	}else log('up-to-date',{map:map.id});
	copyFileSync(keptZip,out);copyFileSync(keptManifest,manifestPath);
	index.push({id:map.id,name:map.name});
}
if(!only)writeFileSync(join(skirmish,'maps.json'),JSON.stringify({schemaVersion:1,maps:index},null,1));
log('done',{maps:index.map(m=>m.id),out:skirmish});
