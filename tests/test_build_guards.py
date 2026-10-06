"""Only authored synthetic fixtures: no proprietary bytecode is needed."""
import importlib.util, hashlib, json, tempfile, unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def module(path):
    spec=importlib.util.spec_from_file_location(path.stem,path)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
class BuildGuards(unittest.TestCase):
    def test_unknown_original_is_rejected(self):
        m=module(ROOT/'native-integration/native_build.py')
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'input.apk';p.write_bytes(b'not an APK');m.ORIGINAL=p
            with self.assertRaisesRegex(RuntimeError,'Unrecognized original APK'):m.preflight()
    def test_original_cannot_overlap_output(self):
        m=module(ROOT/'native-integration/native_build.py')
        m.ORIGINAL=m.BUILD/'original-dex-input.apk'
        with self.assertRaisesRegex(RuntimeError,'outside the build output'):m.validate_input_location()
    def test_host_service_pin_is_original(self):
        m=module(ROOT/'native-integration/patch_controller_host.py')
        self.assertEqual(m.PINS[m.SERVICE_PATH],'b557ad541adfb8cadf5fb2e379cb2caf12704e44653312a3a45c54cad9ba12c4')
    def test_audio_generator_refuses_unknown_input(self):
        m=module(ROOT/'native-integration/patch_microphone_route.py')
        with tempfile.TemporaryDirectory() as d:
            src=Path(d)/'source';out=Path(d)/'output'
            p=src/m.RECORDER;p.parent.mkdir(parents=True);p.write_text('synthetic invalid fixture')
            with self.assertRaisesRegex(ValueError,'Unrecognized'):m.generate(src,out)
            self.assertFalse(out.exists())
    def test_pinned_dictionary_sources(self):
        data=json.loads((ROOT/'quick-engine/provenance.json').read_text())
        for item in data['sources']:
            p=ROOT/'quick-engine'/item['local_path']
            self.assertEqual(hashlib.sha256(p.read_bytes()).hexdigest(),item['sha256'])
    def test_start_callback_only_passes_service(self):
        text=(ROOT/'native-integration/patch_controller_host.py').read_text()
        self.assertIn('->start({SERVICE})V',text)
        self.assertIn('->viewStarted({SERVICE})V',text)
        self.assertNotIn('->start({SERVICE}Landroid/view/inputmethod/EditorInfo;)V',text)
if __name__=='__main__':unittest.main()
