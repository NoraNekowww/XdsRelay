import json, os, sys
import fitdecode

OUT = sys.argv[1] if len(sys.argv) > 1 else '.'
FIT = os.path.join(OUT, 'gen_test.fit')
EXP = os.path.join(OUT, 'expected.json')

with open(EXP, encoding='utf-8') as f:
    exp = json.load(f)

recs = []
file_id = session = lap = activity = None
events = []
fit = fitdecode.FitReader(FIT)
with fit as f:
    for fr in f:
        if fr.frame_type != fitdecode.FIT_FRAME_DATA:
            continue
        if fr.name == 'record':
            recs.append({d.name: d.value for d in fr.fields})
        elif fr.name == 'file_id':
            file_id = {d.name: d.value for d in fr.fields}
        elif fr.name == 'session':
            session = {d.name: d.value for d in fr.fields}
        elif fr.name == 'lap':
            lap = {d.name: d.value for d in fr.fields}
        elif fr.name == 'activity':
            activity = {d.name: d.value for d in fr.fields}
        elif fr.name == 'event':
            events.append({d.name: d.value for d in fr.fields})

fails = []
def chk(what, want, got, tol):
    if got is None:
        fails.append(f'{what}: 缺失 (期望 {want})')
        return
    if isinstance(want, (int, float)):
        if abs(float(got) - float(want)) > tol:
            fails.append(f'{what}: 期望 {want} 得到 {got} (容差 {tol})')
    else:
        if got != want:
            fails.append(f'{what}: 期望 {want} 得到 {got}')

print(f'解析出 record {len(recs)} 条, event {len(events)} 条')
print('file_id :', {k: str(v) for k, v in (file_id or {}).items()})
print('event   :', events)
print('activity:', {k: str(v) for k, v in (activity or {}).items()})

chk('record 条数', exp['records'], len(recs), 0)

SEMI = 180.0 / (1 << 31)

def deg(v):
    return None if v is None else v * SEMI

r0, rN = recs[0], recs[-1]
chk('首点纬度', exp['first_lat'], deg(r0.get('position_lat')), 1e-6)
chk('首点经度', exp['first_lon'], deg(r0.get('position_long')), 1e-6)
chk('末点纬度', exp['last_lat'], deg(rN.get('position_lat')), 1e-6)
chk('末点经度', exp['last_lon'], deg(rN.get('position_long')), 1e-6)
chk('末点海拔', exp['last_alt'], rN.get('altitude'), 0.2 + 1e-6)
chk('末点速度', exp['last_speed'], rN.get('speed'), 0.001 + 1e-9)
chk('末点踏频', exp['last_cadence'], rN.get('cadence'), 0)
chk('末点功率', exp['last_power'], rN.get('power'), 0)
chk('末点里程', exp['last_distance'], rN.get('distance'), 0.02)
chk('首条时间戳', exp['start_epoch'], r0.get('timestamp').timestamp(), 0.5)
chk('末条时间戳', exp['end_epoch'], rN.get('timestamp').timestamp(), 0.5)

print()
print('session :', {k: (round(v,3) if isinstance(v,float) else v) for k, v in (session or {}).items() if k in
                    ('total_elapsed_time','total_timer_time','total_distance','avg_speed','max_speed',
                     'avg_cadence','max_cadence','avg_power','max_power','normalized_power',
                     'total_ascent','total_descent','sport','sub_sport','max_altitude','min_altitude')})

if session is None:
    fails.append('session 消息缺失')
else:
    chk('session 总里程', exp['total_distance'], session.get('total_distance'), 0.02)
    chk('session 运动时间', exp['total_timer'], session.get('total_timer_time'), 0.001)
    chk('session 总时间', exp['total_elapsed'], session.get('total_elapsed_time'), 0.001)
    chk('session 均速', exp['avg_speed'], session.get('avg_speed'), 0.001)
    chk('session 最高速', exp['max_speed'], session.get('max_speed'), 0.001)
    chk('session 均踏频', exp['avg_cadence'], session.get('avg_cadence'), 0)
    chk('session 最高踏频', exp['max_cadence'], session.get('max_cadence'), 0)
    chk('session 均功率', exp['avg_power'], session.get('avg_power'), 0)
    chk('session 最大功率', exp['max_power'], session.get('max_power'), 0)
    chk('session NP', exp['np'], session.get('normalized_power'), 0)
    chk('session 累计爬升', exp['ascent'], session.get('total_ascent'), 0)
    chk('session 累计下降', exp['descent'], session.get('total_descent'), 0)
    chk('session 运动类型', 'cycling', session.get('sport'), 0)

if lap is None:
    fails.append('lap 消息缺失')
else:
    chk('lap 总里程', exp['total_distance'], lap.get('total_distance'), 0.02)
    chk('lap 均功率', exp['avg_power'], lap.get('avg_power'), 0)
    chk('lap NP', exp['np'], lap.get('normalized_power'), 0)

if activity is None:
    fails.append('activity 消息缺失')
else:
    chk('activity 会话数', 1, activity.get('num_sessions'), 0)

if file_id is None:
    fails.append('file_id 缺失')
else:
    chk('file_id 类型', 'activity', file_id.get('type'), 0)

print()
if fails:
    print(f'✗ FIT 校验失败 {len(fails)} 项:')
    for x in fails:
        print('   -', x)
    sys.exit(1)
print('✓ FIT 校验通过：文件头/CRC 合法，record/lap/session/activity 全部字段与期望一致')
