#!/usr/bin/env python3
"""
Journey Test Suite Validator.

Validates:
1. All XML journey descriptors have valid XML syntax and adhere to the Journey schema.
2. The TestPlanManifest JSON is valid and strictly matches all journey files.
3. The target APK artifact exists on disk.
"""

import json
import os
import sys
import xml.etree.ElementTree as ET

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.abspath(os.path.join(script_dir, "../../.."))
    manifest_path = os.path.join(script_dir, "test_plan_manifest.json")

    print(f"[1/4] Checking TestPlanManifest at {manifest_path}...")
    if not os.path.exists(manifest_path):
        print("ERROR: test_plan_manifest.json not found!")
        sys.exit(1)

    with open(manifest_path, "r", encoding="utf-8") as f:
        try:
            manifest = json.load(f)
        except Exception as e:
            print(f"ERROR: Failed to parse test_plan_manifest.json: {e}")
            sys.exit(1)

    print(f"  Plan ID: {manifest.get('planId')}")
    print(f"  Title: {manifest.get('title')}")
    print(f"  Version: {manifest.get('version')}")

    # Check target APK
    apk_rel_path = manifest.get("targetApp", {}).get("apkRelativePath")
    apk_full_path = os.path.join(project_root, apk_rel_path)
    print(f"[2/4] Checking target APK at {apk_full_path}...")
    if not os.path.exists(apk_full_path):
        print(f"ERROR: Target APK does not exist at {apk_full_path}!")
        sys.exit(1)
    apk_size_mb = os.path.getsize(apk_full_path) / (1024 * 1024)
    print(f"  APK found! Size: {apk_size_mb:.2f} MB")

    # Collect all referenced journey files
    modules = manifest.get("modules", [])
    abnormal = manifest.get("abnormalScenarios", [])
    expected_journeys = []

    for mod in modules:
        expected_journeys.append((mod["id"], mod["journeyFile"]))
    for exc in abnormal:
        expected_journeys.append((exc["id"], exc["journeyFile"]))

    print(f"[3/4] Validating {len(expected_journeys)} Journey XML descriptor files...")
    all_ok = True
    validated_files = []

    for case_id, filename in expected_journeys:
        journey_path = os.path.join(script_dir, filename)
        if not os.path.exists(journey_path):
            print(f"  [FAIL] {case_id}: File not found: {filename}")
            all_ok = False
            continue

        try:
            tree = ET.parse(journey_path)
            root = tree.getroot()

            if root.tag != "journey":
                print(f"  [FAIL] {case_id}: Root tag is not <journey>, got <{root.tag}>")
                all_ok = False
                continue

            journey_name = root.attrib.get("name")
            if not journey_name:
                print(f"  [FAIL] {case_id}: <journey> missing 'name' attribute")
                all_ok = False
                continue

            desc = root.find("description")
            if desc is None or not (desc.text and desc.text.strip()):
                print(f"  [FAIL] {case_id}: Missing or empty <description>")
                all_ok = False
                continue

            actions_elem = root.find("actions")
            if actions_elem is None:
                print(f"  [FAIL] {case_id}: Missing <actions> element")
                all_ok = False
                continue

            action_nodes = actions_elem.findall("action")
            if not action_nodes:
                print(f"  [FAIL] {case_id}: No <action> elements inside <actions>")
                all_ok = False
                continue

            empty_actions = [i for i, a in enumerate(action_nodes) if not (a.text and a.text.strip())]
            if empty_actions:
                print(f"  [FAIL] {case_id}: Empty action at indices: {empty_actions}")
                all_ok = False
                continue

            validated_files.append((case_id, filename, len(action_nodes), journey_name))
            print(f"  [PASS] {case_id} ({filename}): {len(action_nodes)} actions. Title: '{journey_name}'")

        except ET.ParseError as e:
            print(f"  [FAIL] {case_id}: XML ParseError in {filename}: {e}")
            all_ok = False

    print("[4/4] Summary:")
    print(f"  Total Journeys Validated: {len(validated_files)}/{len(expected_journeys)}")
    print(f"  Modules (MOD-01~08): {len(modules)}")
    print(f"  Abnormal Scenarios (EXC-01~08): {len(abnormal)}")

    if all_ok:
        print("\nALL TEST JOURNEYS AND MANIFEST VERIFICATION PASSED SUCCESSFULLY.")
        sys.exit(0)
    else:
        print("\nSOME JOURNEY FILES FAILED VALIDATION.")
        sys.exit(1)

if __name__ == "__main__":
    main()
